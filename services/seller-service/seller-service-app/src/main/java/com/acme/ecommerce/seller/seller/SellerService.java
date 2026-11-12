package com.acme.ecommerce.seller.seller;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.seller.Paged;
import com.acme.ecommerce.seller.client.AuthFeignClient;
import com.acme.ecommerce.seller.client.NotificationFeignClient;
import com.acme.ecommerce.seller.client.ProductFeignClient;
import com.acme.ecommerce.seller.crypto.DataCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Seller onboarding/KYC, admin decisions, public profile and internal lookups. */
@Service
public class SellerService {

  private static final Logger log = LoggerFactory.getLogger(SellerService.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
  private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
  private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
  private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{9,18}$");
  private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{8,15}$");

  /** KYC application body. Bank account number is write-only: it is never returned. */
  public record ApplyRequest(
      String legalName,
      String displayName,
      String email,
      String phone,
      String gstin,
      String pan,
      String bankAccountNumber,
      String ifsc,
      Map<String, Object> pickupAddress,
      Boolean submit) {}

  public record Seller(
      UUID id,
      UUID userId,
      String legalName,
      String displayName,
      String slug,
      String email,
      String phone,
      String gstin,
      String pan,
      String bankAccountLast4,
      String ifsc,
      Map<String, Object> pickupAddress,
      String status,
      String rejectionReason,
      BigDecimal commissionPercent,
      BigDecimal ratingAvg,
      int ratingCount,
      OffsetDateTime createdAt,
      OffsetDateTime approvedAt) {}

  public record PublicProfile(
      UUID id,
      String slug,
      String displayName,
      BigDecimal ratingAvg,
      int ratingCount,
      OffsetDateTime joinedAt,
      long productCount) {}

  public record InternalSeller(
      UUID sellerId,
      String slug,
      String displayName,
      BigDecimal ratingAvg,
      int ratingCount,
      String status) {}

  public record ByUser(UUID sellerId, String status) {}

  private final JdbcClient jdbc;
  private final DataCipher cipher;
  private final AuthFeignClient auth;
  private final NotificationFeignClient notifications;
  private final ProductFeignClient products;
  private final String adminUrl;

  public SellerService(
      JdbcClient jdbc,
      DataCipher cipher,
      AuthFeignClient auth,
      NotificationFeignClient notifications,
      ProductFeignClient products,
      @Value("${seller.admin-url:http://localhost:3001}") String adminUrl) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.auth = auth;
    this.notifications = notifications;
    this.products = products;
    this.adminUrl = adminUrl;
  }

  // ---- mapping ---------------------------------------------------------------------------

  private static Seller map(ResultSet rs, int n) throws SQLException {
    Map<String, Object> pickup = null;
    String json = rs.getString("pickup_address");
    if (json != null) {
      try {
        pickup = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
      } catch (java.io.IOException e) {
        pickup = null;
      }
    }
    return new Seller(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("legal_name"),
        rs.getString("display_name"),
        rs.getString("slug"),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("gstin"),
        rs.getString("pan"),
        rs.getString("bank_account_last4"),
        rs.getString("ifsc"),
        pickup,
        rs.getString("status"),
        rs.getString("rejection_reason"),
        rs.getBigDecimal("commission_percent"),
        rs.getBigDecimal("rating_avg"),
        rs.getInt("rating_count"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("approved_at", OffsetDateTime.class));
  }

  @Transactional(readOnly = true)
  public Optional<Seller> findByUser(UUID userId) {
    return jdbc.sql("SELECT * FROM sellers WHERE user_id = :u").param("u", userId).query(SellerService::map).optional();
  }

  @Transactional(readOnly = true)
  public Optional<Seller> findById(UUID id) {
    return jdbc.sql("SELECT * FROM sellers WHERE id = :i").param("i", id).query(SellerService::map).optional();
  }

  /** The caller's seller record or 403 (used by every /api/seller/** endpoint). */
  @Transactional(readOnly = true)
  public Seller requireByUser(UUID userId) {
    return findByUser(userId)
        .orElseThrow(() -> new CodedException(ErrorCode.FORBIDDEN, "You do not have a seller account"));
  }

  // ---- onboarding ------------------------------------------------------------------------

  @Transactional
  public Seller apply(UUID userId, String jwtEmail, ApplyRequest r) {
    Optional<Seller> existing = findByUser(userId);
    if (existing.isPresent()
        && !List.of("DRAFT", "REJECTED").contains(existing.get().status())) {
      throw new CodedException(
          ErrorCode.RESOURCE_CONFLICT,
          "Application cannot be changed while it is " + existing.get().status());
    }
    boolean submit = r.submit() == null || r.submit();
    Map<String, String> errors = new LinkedHashMap<>();

    String legalName = trim(r.legalName());
    String displayName = trim(r.displayName());
    String email = trim(r.email()) != null ? trim(r.email()) : jwtEmail;
    String phone = trim(r.phone());
    String gstin = upper(r.gstin());
    String pan = upper(r.pan());
    String ifsc = upper(r.ifsc());
    String account = r.bankAccountNumber() == null ? null : r.bankAccountNumber().replaceAll("\\s", "");
    if (account != null && account.isEmpty()) {
      account = null;
    }

    if (gstin != null && !GSTIN.matcher(gstin).matches()) {
      errors.put("gstin", "Invalid GSTIN format");
    }
    if (pan != null && !PAN.matcher(pan).matches()) {
      errors.put("pan", "Invalid PAN format");
    }
    if (gstin != null && pan != null && !errors.containsKey("gstin") && !errors.containsKey("pan")
        && !gstin.substring(2, 12).equals(pan)) {
      errors.put("pan", "PAN must match characters 3-12 of the GSTIN");
    }
    if (ifsc != null && !IFSC.matcher(ifsc).matches()) {
      errors.put("ifsc", "Invalid IFSC format");
    }
    if (account != null && !ACCOUNT.matcher(account).matches()) {
      errors.put("bankAccountNumber", "Bank account number must be 9-18 digits");
    }
    if (phone != null && !PHONE.matcher(phone).matches()) {
      errors.put("phone", "Invalid phone number");
    }
    if (legalName != null && legalName.length() > 200) {
      errors.put("legalName", "Too long");
    }
    if (displayName != null && (displayName.length() < 2 || displayName.length() > 120)) {
      errors.put("displayName", "Must be 2-120 characters");
    }
    Seller prev = existing.orElse(null);
    if (submit) {
      require(errors, "legalName", legalName != null || (prev != null && prev.legalName() != null));
      require(errors, "displayName", displayName != null || prev != null);
      require(errors, "gstin", gstin != null || (prev != null && prev.gstin() != null));
      require(errors, "pan", pan != null || (prev != null && prev.pan() != null));
      require(errors, "ifsc", ifsc != null || (prev != null && prev.ifsc() != null));
      require(errors, "bankAccountNumber", account != null || (prev != null && prev.bankAccountLast4() != null));
      require(errors, "pickupAddress",
          (r.pickupAddress() != null && !r.pickupAddress().isEmpty())
              || (prev != null && prev.pickupAddress() != null));
      require(errors, "email", email != null);
    } else {
      require(errors, "displayName", displayName != null || prev != null);
    }
    if (!errors.isEmpty()) {
      throw new ValidationException("Seller application is invalid", errors);
    }

    String status = submit ? "SUBMITTED" : "DRAFT";
    String pickupJson = null;
    if (r.pickupAddress() != null && !r.pickupAddress().isEmpty()) {
      try {
        pickupJson = JSON.writeValueAsString(r.pickupAddress());
      } catch (java.io.IOException e) {
        throw new ValidationException("pickupAddress is not valid JSON");
      }
    }
    String last4 = account == null ? null : account.substring(account.length() - 4);
    String enc = account == null ? null : cipher.encrypt(account);

    if (prev == null) {
      String slug = uniqueSlug(displayName);
      jdbc.sql(
              "INSERT INTO sellers (user_id, legal_name, display_name, slug, email, phone, gstin, pan,"
                  + " bank_account_last4, bank_account_hash, ifsc, pickup_address, status)"
                  + " VALUES (:u, :ln, :dn, :slug, :em, :ph, :gst, :pan, :l4, :enc, :ifsc,"
                  + " CAST(:pa AS jsonb), :st)")
          .param("u", userId)
          .param("ln", legalName)
          .param("dn", displayName)
          .param("slug", slug)
          .param("em", email)
          .param("ph", phone)
          .param("gst", gstin)
          .param("pan", pan)
          .param("l4", last4)
          .param("enc", enc)
          .param("ifsc", ifsc)
          .param("pa", pickupJson)
          .param("st", status)
          .update();
    } else {
      jdbc.sql(
              "UPDATE sellers SET legal_name = COALESCE(:ln, legal_name), display_name = COALESCE(:dn,"
                  + " display_name), email = COALESCE(:em, email), phone = COALESCE(:ph, phone), gstin ="
                  + " COALESCE(:gst, gstin), pan = COALESCE(:pan, pan), bank_account_last4 ="
                  + " COALESCE(:l4, bank_account_last4), bank_account_hash = COALESCE(:enc,"
                  + " bank_account_hash), ifsc = COALESCE(:ifsc, ifsc), pickup_address ="
                  + " COALESCE(CAST(:pa AS jsonb), pickup_address), status = :st, rejection_reason ="
                  + " NULL, updated_at = now() WHERE id = :id")
          .param("ln", legalName)
          .param("dn", displayName)
          .param("em", email)
          .param("ph", phone)
          .param("gst", gstin)
          .param("pan", pan)
          .param("l4", last4)
          .param("enc", enc)
          .param("ifsc", ifsc)
          .param("pa", pickupJson)
          .param("st", status)
          .param("id", prev.id())
          .update();
    }
    log.info("Seller application by user {} -> {}", userId, status);
    return findByUser(userId).orElseThrow();
  }

  private static void require(Map<String, String> errors, String field, boolean present) {
    if (!present) {
      errors.put(field, "Required");
    }
  }

  private static String trim(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String upper(String s) {
    return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
  }

  private String uniqueSlug(String displayName) {
    String base =
        Normalizer.normalize(displayName, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    if (base.isEmpty()) {
      base = "seller";
    }
    if (base.length() > 120) {
      base = base.substring(0, 120);
    }
    String slug = base;
    while (jdbc.sql("SELECT count(*) FROM sellers WHERE slug = :s").param("s", slug).query(Long.class).single() > 0) {
      slug = base + "-" + UUID.randomUUID().toString().substring(0, 4);
    }
    return slug;
  }

  // ---- admin -----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Paged<Seller> adminList(String status, int page, int size) {
    int s = Paged.clampSize(size);
    String where = status == null || status.isBlank() ? "" : " WHERE status = :st";
    var count = jdbc.sql("SELECT count(*) FROM sellers" + where);
    var list = jdbc.sql("SELECT * FROM sellers" + where + " ORDER BY created_at DESC LIMIT :l OFFSET :o");
    if (!where.isEmpty()) {
      count = count.param("st", status.toUpperCase(Locale.ROOT));
      list = list.param("st", status.toUpperCase(Locale.ROOT));
    }
    long total = count.query(Long.class).single();
    List<Seller> rows = list.param("l", s).param("o", (long) Math.max(page, 0) * s).query(SellerService::map).list();
    return Paged.of(rows, Math.max(page, 0), s, total);
  }

  @Transactional(readOnly = true)
  public Seller adminGet(UUID id) {
    return findById(id).orElseThrow(() -> new ResourceNotFoundException("Seller", id));
  }

  @Transactional
  public Seller decide(UUID id, boolean approve, String reason) {
    Seller s = adminGet(id);
    if (approve) {
      if (!List.of("SUBMITTED", "SUSPENDED").contains(s.status())) {
        throw new CodedException(ErrorCode.INVALID_STATUS_TRANSITION, "Cannot approve a " + s.status() + " seller");
      }
      jdbc.sql("UPDATE sellers SET status = 'APPROVED', rejection_reason = NULL, approved_at = COALESCE(approved_at, now()), updated_at = now() WHERE id = :id")
          .param("id", id)
          .update();
      try {
        auth.grantRole(s.userId(), new AuthFeignClient.GrantRole("SELLER"));
      } catch (RuntimeException e) {
        // Throwing rolls the status change back: an APPROVED seller without the role is worse.
        throw new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Could not grant the seller role; try again", e);
      }
    } else {
      if (!"SUBMITTED".equals(s.status())) {
        throw new CodedException(ErrorCode.INVALID_STATUS_TRANSITION, "Cannot reject a " + s.status() + " seller");
      }
      if (reason == null || reason.isBlank()) {
        throw ValidationException.forField("reason", "A reason is required when rejecting");
      }
      jdbc.sql("UPDATE sellers SET status = 'REJECTED', rejection_reason = :r, updated_at = now() WHERE id = :id")
          .param("r", reason.length() > 500 ? reason.substring(0, 500) : reason)
          .param("id", id)
          .update();
    }
    Seller after = adminGet(id);
    sendDecisionEmail(after, approve);
    return after;
  }

  @Transactional
  public Seller suspend(UUID id, String reason) {
    Seller s = adminGet(id);
    if ("00000000-0000-0000-0000-00000000a11e".equals(id.toString())) {
      throw new CodedException(ErrorCode.DOMAIN_RULE_VIOLATION, "The platform seller cannot be suspended");
    }
    if (!"APPROVED".equals(s.status())) {
      throw new CodedException(ErrorCode.INVALID_STATUS_TRANSITION, "Only an APPROVED seller can be suspended");
    }
    jdbc.sql("UPDATE sellers SET status = 'SUSPENDED', rejection_reason = :r, updated_at = now() WHERE id = :id")
        .param("r", reason == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason))
        .param("id", id)
        .update();
    return adminGet(id);
  }

  private void sendDecisionEmail(Seller s, boolean approve) {
    if (s.email() == null) {
      return;
    }
    try {
      Map<String, String> data = new LinkedHashMap<>();
      data.put("name", s.legalName() == null ? s.displayName() : s.legalName());
      data.put("storeName", s.displayName());
      data.put("link", adminUrl + "/seller");
      if (!approve && s.rejectionReason() != null) {
        data.put("reason", s.rejectionReason());
      }
      notifications.sendEmail(
          new NotificationFeignClient.EmailBody(
              s.email(), approve ? "SELLER_APPROVED" : "SELLER_REJECTED", data));
    } catch (RuntimeException e) {
      log.warn("Could not send seller decision email: {}", e.toString());
    }
  }

  // ---- public + internal -----------------------------------------------------------------

  @Transactional(readOnly = true)
  public PublicProfile profile(String slug) {
    Seller s =
        jdbc.sql("SELECT * FROM sellers WHERE slug = :s AND status = 'APPROVED'")
            .param("s", slug)
            .query(SellerService::map)
            .optional()
            .orElseThrow(() -> new ResourceNotFoundException("Seller", slug));
    return new PublicProfile(
        s.id(), s.slug(), s.displayName(), s.ratingAvg(), s.ratingCount(), s.approvedAt() != null ? s.approvedAt() : s.createdAt(), productCount(s.id()));
  }

  private long productCount(UUID sellerId) {
    try {
      Map<String, Object> page = products.list(sellerId, 1);
      Object p = page.get("page");
      if (p instanceof Map<?, ?> pm && pm.get("totalElements") instanceof Number n) {
        return n.longValue();
      }
      if (page.get("totalElements") instanceof Number n) {
        return n.longValue();
      }
    } catch (RuntimeException e) {
      log.debug("productCount unavailable: {}", e.toString());
    }
    return 0;
  }

  /** Rating used by the buy box: neutral 4.0 until the seller has at least one rating. */
  private static InternalSeller internal(Seller s) {
    BigDecimal rating = s.ratingCount() == 0 ? new BigDecimal("4.00") : s.ratingAvg();
    return new InternalSeller(s.id(), s.slug(), s.displayName(), rating, s.ratingCount(), s.status());
  }

  @Transactional(readOnly = true)
  public ByUser byUser(UUID userId) {
    Seller s = findByUser(userId).orElseThrow(() -> new ResourceNotFoundException("Seller for user", userId));
    return new ByUser(s.id(), s.status());
  }

  @Transactional(readOnly = true)
  public InternalSeller internalGet(UUID id) {
    return internal(adminGet(id));
  }

  @Transactional(readOnly = true)
  public List<InternalSeller> internalBatch(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT * FROM sellers WHERE id IN (:ids)")
        .param("ids", ids)
        .query(SellerService::map)
        .list()
        .stream()
        .map(SellerService::internal)
        .toList();
  }
}
