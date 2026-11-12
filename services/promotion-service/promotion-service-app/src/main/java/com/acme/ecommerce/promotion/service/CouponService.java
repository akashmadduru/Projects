package com.acme.ecommerce.promotion.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.promotion.api.dto.AvailableCouponResponse;
import com.acme.ecommerce.promotion.api.dto.CouponRequest;
import com.acme.ecommerce.promotion.api.dto.CouponResponse;
import com.acme.ecommerce.promotion.api.dto.CouponValidationResponse;
import com.acme.ecommerce.promotion.api.dto.RedeemRequest;
import com.acme.ecommerce.promotion.api.dto.RedeemResponse;
import com.acme.ecommerce.promotion.api.dto.ValidateCouponRequest;
import com.acme.ecommerce.promotion.repository.CouponRepository;
import com.acme.ecommerce.promotion.repository.CouponRepository.Coupon;
import com.acme.ecommerce.promotion.repository.CouponRepository.Redemption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coupon rules, redemption and admin CRUD. */
@Service
public class CouponService {

  private final CouponRepository coupons;

  public CouponService(CouponRepository coupons) {
    this.coupons = coupons;
  }

  private static String norm(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  // --- customer -------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public CouponValidationResponse validate(UUID userId, ValidateCouponRequest req) {
    Coupon c = coupons.findByCode(norm(req.code())).orElseThrow(CouponService::invalid);
    String currency = req.currency().toUpperCase(Locale.ROOT);
    BigDecimal discount = evaluate(c, userId, req.subtotal(), currency, req.firstOrder());
    return new CouponValidationResponse(c.code(), c.description(), discount, currency);
  }

  @Transactional(readOnly = true)
  public List<AvailableCouponResponse> available(
      UUID userId, BigDecimal subtotal, String currency) {
    String cur = currency == null ? null : currency.toUpperCase(Locale.ROOT);
    return coupons.findActive().stream()
        .filter(c -> c.currency() == null || cur == null || c.currency().equals(cur))
        .filter(c -> !(c.startsAt() != null && Instant.now().isBefore(c.startsAt())))
        .filter(c -> !(c.endsAt() != null && Instant.now().isAfter(c.endsAt())))
        .filter(c -> c.totalLimit() == null || c.redeemed() < c.totalLimit())
        .filter(
            c ->
                c.perUserLimit() == null
                    || coupons.userRedemptions(c.id(), userId) < c.perUserLimit())
        .map(
            c -> {
              BigDecimal discount = null;
              if (subtotal != null && cur != null) {
                try {
                  discount = evaluate(c, userId, subtotal, cur, null);
                } catch (CodedException ignored) {
                  // below the minimum etc.: still listed, without an amount
                }
              }
              return new AvailableCouponResponse(
                  c.code(), c.description(), discount, c.minOrderAmount());
            })
        .toList();
  }

  // --- internal -------------------------------------------------------------------------

  /** Idempotent by orderId. */
  @Transactional
  public RedeemResponse redeem(RedeemRequest req) {
    Coupon c = coupons.findByCode(norm(req.code())).orElseThrow(CouponService::invalid);
    coupons.lock(c.id());
    Redemption existing = coupons.findRedemption(req.orderId()).orElse(null);
    if (existing != null && "APPLIED".equals(existing.status())) {
      return new RedeemResponse(existing.discountAmount());
    }
    // re-read under the lock so the limit counters are current
    c = coupons.findById(c.id()).orElseThrow(CouponService::invalid);
    String currency = req.currency().toUpperCase(Locale.ROOT);
    BigDecimal discount = evaluate(c, req.userId(), req.subtotal(), currency, req.firstOrder());
    if (existing == null) {
      coupons.insertRedemption(c.id(), req.userId(), req.orderId(), discount);
    } else {
      coupons.reapplyRedemption(existing.id(), c.id(), req.userId(), discount);
    }
    return new RedeemResponse(discount);
  }

  /** Idempotent. */
  @Transactional
  public void release(UUID orderId) {
    coupons.release(orderId);
  }

  // --- admin ----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<CouponResponse> list() {
    return coupons.findAll().stream().map(CouponService::view).toList();
  }

  @Transactional
  public CouponResponse create(CouponRequest req) {
    validateShape(req);
    UUID id;
    try {
      id = coupons.insert(req, norm(req.code()));
    } catch (DuplicateKeyException e) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Coupon code already exists");
    }
    return view(coupons.findById(id).orElseThrow());
  }

  @Transactional
  public CouponResponse update(UUID id, CouponRequest req) {
    validateShape(req);
    if (coupons.update(id, req) == 0) {
      throw notFound(id);
    }
    return view(coupons.findById(id).orElseThrow());
  }

  @Transactional
  public CouponResponse deactivate(UUID id) {
    if (coupons.deactivate(id) == 0) {
      throw notFound(id);
    }
    return view(coupons.findById(id).orElseThrow());
  }

  // --- rules ----------------------------------------------------------------------------

  /** Returns the discount or throws the coupon error that explains why it does not apply. */
  private BigDecimal evaluate(
      Coupon c, UUID userId, BigDecimal subtotal, String currency, Boolean firstOrder) {
    Instant now = Instant.now();
    if (!c.active()) {
      throw invalid();
    }
    if (c.startsAt() != null && now.isBefore(c.startsAt())) {
      throw new CodedException(ErrorCode.COUPON_NOT_APPLICABLE, "This coupon is not active yet");
    }
    if (c.endsAt() != null && now.isAfter(c.endsAt())) {
      throw new CodedException(ErrorCode.COUPON_EXPIRED, "This coupon has expired");
    }
    if (c.currency() != null && !c.currency().equalsIgnoreCase(currency)) {
      throw new CodedException(
          ErrorCode.COUPON_NOT_APPLICABLE,
          "This coupon is only valid for " + c.currency() + " orders");
    }
    if (subtotal.compareTo(c.minOrderAmount()) < 0) {
      throw new CodedException(
          ErrorCode.COUPON_NOT_APPLICABLE,
          "Add items worth at least "
              + c.minOrderAmount().stripTrailingZeros().toPlainString()
              + " to use this coupon");
    }
    if (c.firstOrderOnly() && Boolean.FALSE.equals(firstOrder)) {
      throw new CodedException(
          ErrorCode.COUPON_NOT_APPLICABLE, "This coupon is only valid on your first order");
    }
    if (c.totalLimit() != null && c.redeemed() >= c.totalLimit()) {
      throw new CodedException(ErrorCode.COUPON_USAGE_LIMIT, "This coupon is fully redeemed");
    }
    if (c.perUserLimit() != null && coupons.userRedemptions(c.id(), userId) >= c.perUserLimit()) {
      throw new CodedException(
          ErrorCode.COUPON_USAGE_LIMIT, "You have already used this coupon the maximum times");
    }
    BigDecimal discount;
    if ("PERCENT".equals(c.type())) {
      discount =
          subtotal.multiply(c.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      if (c.maxDiscount() != null && discount.compareTo(c.maxDiscount()) > 0) {
        discount = c.maxDiscount();
      }
    } else {
      discount = c.value();
    }
    if (discount.compareTo(subtotal) > 0) {
      discount = subtotal;
    }
    return discount.setScale(2, RoundingMode.HALF_UP);
  }

  private static void validateShape(CouponRequest r) {
    if ("FLAT".equals(r.type()) && (r.currency() == null || r.currency().isBlank())) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "A FLAT coupon needs a currency");
    }
    if ("PERCENT".equals(r.type()) && r.value().compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "A percentage cannot exceed 100");
    }
    if (r.startsAt() != null && r.endsAt() != null && r.endsAt().isBefore(r.startsAt())) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "endsAt must be after startsAt");
    }
  }

  private static CouponResponse view(Coupon c) {
    return new CouponResponse(
        c.id(),
        c.code(),
        c.description(),
        c.type(),
        c.value(),
        c.maxDiscount(),
        c.minOrderAmount(),
        c.currency(),
        c.startsAt(),
        c.endsAt(),
        c.totalLimit(),
        c.perUserLimit(),
        c.firstOrderOnly(),
        c.active(),
        c.redeemed(),
        c.createdAt(),
        c.updatedAt());
  }

  private static CodedException invalid() {
    return new CodedException(ErrorCode.COUPON_INVALID, "This coupon code is not valid");
  }

  private static CodedException notFound(UUID id) {
    return new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Coupon not found: " + id);
  }
}
