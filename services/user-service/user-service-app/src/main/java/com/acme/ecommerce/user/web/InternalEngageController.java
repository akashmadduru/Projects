package com.acme.ecommerce.user.web;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.acme.ecommerce.user.membership.MembershipService;
import com.acme.ecommerce.user.membership.MembershipService.Lookup;
import com.acme.ecommerce.user.membership.PreferenceService;
import com.acme.ecommerce.user.membership.PreferenceService.Preferences;
import com.acme.ecommerce.user.membership.ReferralService;
import com.acme.ecommerce.user.membership.ReferralService.ByReferee;
import com.acme.ecommerce.user.service.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoints (X-Internal-Token via InternalSecurityConfig; never routed). */
@RestController
public class InternalEngageController {

  public record PaymentResult(UUID referenceId, String status, String paymentId) {}

  public record Member(UUID userId, String email, String displayName) {}

  public record MemberPage(List<Member> content, int page, boolean last) {}

  private final MembershipService membership;
  private final PreferenceService preferences;
  private final ReferralService referrals;
  private final UserService users;
  private final JdbcClient jdbc;

  public InternalEngageController(
      MembershipService membership,
      PreferenceService preferences,
      ReferralService referrals,
      UserService users,
      JdbcClient jdbc) {
    this.membership = membership;
    this.preferences = preferences;
    this.referrals = referrals;
    this.users = users;
    this.jdbc = jdbc;
  }

  @GetMapping("/internal/users/{userId}/membership")
  public Lookup membership(@PathVariable UUID userId) {
    return membership.lookup(userId);
  }

  @PostMapping("/internal/users/membership/payment-result")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void paymentResult(@RequestBody PaymentResult request) {
    membership.paymentResult(request.referenceId(), request.status(), request.paymentId());
  }

  @GetMapping("/internal/users/{userId}/preferences")
  public Preferences preferences(@PathVariable UUID userId) {
    return preferences.get(userId);
  }

  @GetMapping("/internal/users/{userId}/addresses/{id}")
  public AddressResponse address(@PathVariable UUID userId, @PathVariable UUID id) {
    return users.getAddress(userId, id);
  }

  @GetMapping("/internal/referrals/by-referee/{userId}")
  public ByReferee byReferee(@PathVariable UUID userId) {
    return referrals
        .byReferee(userId)
        .orElseThrow(() -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "No referral"));
  }

  @PostMapping("/internal/referrals/by-referee/{userId}/rewarded")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void rewarded(@PathVariable UUID userId) {
    referrals.markRewarded(userId);
  }

  /**
   * Audience listing for campaigns. ALL and INACTIVE_30D both return every known profile with an
   * email (the caller filters inactivity using order data); MEMBERS returns active members.
   */
  @GetMapping("/internal/users")
  public MemberPage audience(
      @RequestParam(defaultValue = "ALL") String audience,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "200") int size) {
    int sz = Math.min(Math.max(size, 1), 500);
    int pg = Math.max(page, 0);
    String sql =
        "MEMBERS".equalsIgnoreCase(audience)
            ? "SELECT p.user_id, p.email, p.display_name FROM users.user_profiles p JOIN"
                + " users.memberships m ON m.user_id = p.user_id WHERE m.status IN"
                + " ('ACTIVE','CANCELLED') AND m.ends_at > now() AND p.email IS NOT NULL ORDER BY"
                + " p.user_id LIMIT :lim OFFSET :off"
            : "SELECT user_id, email, display_name FROM users.user_profiles p WHERE email IS NOT"
                + " NULL ORDER BY user_id LIMIT :lim OFFSET :off";
    List<Member> rows =
        jdbc.sql(sql)
            .param("lim", sz + 1)
            .param("off", pg * sz)
            .query(
                (rs, n) ->
                    new Member(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name")))
            .list();
    boolean last = rows.size() <= sz;
    return new MemberPage(last ? rows : rows.subList(0, sz), pg, last);
  }
}
