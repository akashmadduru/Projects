package com.acme.ecommerce.promotion.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.promotion.repository.CouponRepository;
import com.acme.ecommerce.promotion.repository.CouponRepository.Coupon;
import com.acme.ecommerce.promotion.repository.DealRepository;
import com.acme.ecommerce.promotion.repository.DealRepository.BankOffer;
import com.acme.ecommerce.promotion.repository.DealRepository.Deal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Display offers (coupons, bank offers, EMI) plus deals of the day and their claims. */
@Service
public class OfferService {

  public record CouponOffer(String code, String description, BigDecimal discountAmount) {}

  public record BankOfferView(UUID id, String bank, String title, BigDecimal discountAmount) {}

  public record EmiPlan(
      int months, BigDecimal monthlyAmount, BigDecimal interestRate, boolean noCost) {}

  public record Offers(
      List<CouponOffer> coupons, List<BankOfferView> bankOffers, List<EmiPlan> emi) {}

  public record DealView(
      UUID id,
      UUID productId,
      UUID variantId,
      String title,
      BigDecimal dealPrice,
      String currency,
      Instant startsAt,
      Instant endsAt,
      int quantityLimit,
      int claimed,
      int remaining,
      Instant membersOnlyUntil) {}

  public record PriceKey(UUID productId, UUID variantId) {}

  public record DealPrice(
      UUID productId, UUID variantId, UUID dealId, BigDecimal dealPrice, int remaining) {}

  public record ClaimItem(UUID dealId, int quantity) {}

  private final CouponRepository coupons;
  private final DealRepository deals;
  private final MembershipLookup members;

  public OfferService(
      CouponRepository coupons, DealRepository deals, MembershipLookup members) {
    this.coupons = coupons;
    this.deals = deals;
    this.members = members;
  }

  // ---- offers --------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Offers offers(BigDecimal price, String currency) {
    if (price == null || currency == null || currency.isBlank()) {
      return new Offers(List.of(), List.of(), List.of());
    }
    String cur = currency.toUpperCase(Locale.ROOT);
    Instant now = Instant.now();
    List<CouponOffer> cs = new ArrayList<>();
    for (Coupon c : coupons.findActive()) {
      if ((c.currency() != null && !c.currency().equals(cur))
          || (c.startsAt() != null && now.isBefore(c.startsAt()))
          || (c.endsAt() != null && now.isAfter(c.endsAt()))
          || (c.totalLimit() != null && c.redeemed() >= c.totalLimit())
          || price.compareTo(c.minOrderAmount()) < 0) {
        continue;
      }
      cs.add(
          new CouponOffer(
              c.code(),
              c.description(),
              discount(c.type(), c.value(), c.maxDiscount(), price)));
    }
    List<BankOfferView> banks = new ArrayList<>();
    for (BankOffer b : deals.activeBankOffers()) {
      if ((b.currency() != null && !b.currency().equals(cur))
          || price.compareTo(b.minAmount()) < 0) {
        continue;
      }
      banks.add(
          new BankOfferView(
              b.id(),
              b.bank(),
              b.title(),
              discount(b.discountType(), b.value(), b.maxDiscount(), price)));
    }
    return new Offers(cs, banks, emi(price, cur));
  }

  private static BigDecimal discount(
      String type, BigDecimal value, BigDecimal max, BigDecimal price) {
    BigDecimal d =
        "PERCENT".equals(type)
            ? price.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : value;
    if (max != null && d.compareTo(max) > 0) {
      d = max;
    }
    return d.min(price).setScale(2, RoundingMode.HALF_UP);
  }

  /** 3/6 months no-cost above the threshold, 9/12 months at 14% p.a. Display-only. */
  static List<EmiPlan> emi(BigDecimal price, String cur) {
    BigDecimal threshold = "INR".equals(cur) ? BigDecimal.valueOf(3000) : BigDecimal.valueOf(100);
    if (price.compareTo(threshold) < 0) {
      return List.of();
    }
    List<EmiPlan> plans = new ArrayList<>();
    for (int months : new int[] {3, 6, 9, 12}) {
      boolean noCost = months <= 6;
      BigDecimal rate = noCost ? BigDecimal.ZERO : BigDecimal.valueOf(14);
      BigDecimal monthly;
      if (noCost) {
        monthly = price.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
      } else {
        MathContext mc = new MathContext(20);
        BigDecimal r = rate.divide(BigDecimal.valueOf(1200), mc);
        BigDecimal pow = BigDecimal.ONE.add(r).pow(months, mc);
        monthly =
            price
                .multiply(r)
                .multiply(pow)
                .divide(pow.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
      }
      plans.add(new EmiPlan(months, monthly, rate, noCost));
    }
    return plans;
  }

  // ---- deals ---------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<DealView> activeDeals(UUID productId, UUID userId) {
    boolean member = userId != null && members.isMember(userId);
    return deals.findActive(productId).stream()
        .filter(d -> visible(d, member))
        .map(OfferService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Deal> adminList() {
    return deals.findAll();
  }

  @Transactional
  public Deal create(Deal d) {
    return deals.insert(d);
  }

  @Transactional
  public Deal update(UUID id, Deal d) {
    return deals.update(id, d).orElseThrow(() -> notFound(id));
  }

  @Transactional
  public Deal deactivate(UUID id) {
    return deals.deactivate(id).orElseThrow(() -> notFound(id));
  }

  @Transactional(readOnly = true)
  public List<DealPrice> prices(List<PriceKey> keys, UUID userId) {
    boolean member = userId != null && members.isMember(userId);
    List<DealPrice> out = new ArrayList<>();
    List<Deal> active = deals.findActive(null).stream().filter(d -> visible(d, member)).toList();
    for (PriceKey k : keys) {
      active.stream()
          .filter(d -> d.productId().equals(k.productId()))
          .filter(d -> d.variantId() == null || d.variantId().equals(k.variantId()))
          .findFirst()
          .ifPresent(
              d ->
                  out.add(
                      new DealPrice(
                          k.productId(),
                          k.variantId(),
                          d.id(),
                          d.dealPrice(),
                          d.quantityLimit() - d.claimed())));
    }
    return out;
  }

  /** Idempotent by orderId: an order that already holds claims is left as is. */
  @Transactional
  public void claim(UUID orderId, List<ClaimItem> items) {
    if (deals.hasClaims(orderId)) {
      return;
    }
    for (ClaimItem i : items) {
      if (!deals.tryClaim(i.dealId(), i.quantity())) {
        throw new CodedException(ErrorCode.DEAL_SOLD_OUT, "This deal has sold out");
      }
      deals.insertClaim(orderId, i.dealId(), i.quantity());
    }
  }

  @Transactional
  public void release(UUID orderId) {
    deals.releaseClaims(orderId);
  }

  /** Members-only early access: before members_only_until only members see and get the deal. */
  private static boolean visible(Deal d, boolean member) {
    return member || d.membersOnlyUntil() == null || !d.membersOnlyUntil().isAfter(Instant.now());
  }

  public static DealView view(Deal d) {
    return new DealView(
        d.id(),
        d.productId(),
        d.variantId(),
        d.title(),
        d.dealPrice(),
        d.currency(),
        d.startsAt(),
        d.endsAt(),
        d.quantityLimit(),
        d.claimed(),
        Math.max(0, d.quantityLimit() - d.claimed()),
        d.membersOnlyUntil());
  }

  private static CodedException notFound(UUID id) {
    return new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Deal not found: " + id);
  }
}
