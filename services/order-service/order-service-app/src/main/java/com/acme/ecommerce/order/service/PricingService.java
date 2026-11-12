package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.DeliveryEstimateResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.Pricing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Shipping, GST and delivery-estimate maths. Prices are tax-inclusive. */
@Service
public class PricingService {

  private static final Pattern INDIAN_PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final OrderProperties props;

  public PricingService(OrderProperties props) {
    this.props = props;
  }

  public static boolean isInr(String currency) {
    return "INR".equalsIgnoreCase(currency);
  }

  public Pricing price(
      BigDecimal subtotal, BigDecimal discount, String currency, String shipState) {
    return price(subtotal, discount, BigDecimal.ZERO, false, currency, shipState);
  }

  /**
   * subtotal - discount - coins + shipping (free for members, else by the post-discount threshold),
   * then the inclusive tax portion of the total.
   */
  public Pricing price(
      BigDecimal subtotal,
      BigDecimal discount,
      BigDecimal coinsValue,
      boolean freeShipping,
      String currency,
      String shipState) {
    BigDecimal sub = subtotal.setScale(2, RoundingMode.HALF_UP);
    BigDecimal disc = discount.min(sub).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    BigDecimal afterDiscount = sub.subtract(disc);
    BigDecimal coins =
        coinsValue.min(afterDiscount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    BigDecimal shipping =
        freeShipping ? BigDecimal.ZERO.setScale(2) : shippingFee(afterDiscount, currency);
    BigDecimal total = afterDiscount.subtract(coins).add(shipping);

    if (!isInr(currency)) {
      return new Pricing(
          sub,
          disc,
          shipping,
          total,
          BigDecimal.ZERO.setScale(2),
          "Tax included",
          null,
          null,
          null,
          coins,
          freeShipping);
    }
    BigDecimal rate = props.getTax().getGstPercent();
    BigDecimal tax =
        total.multiply(rate).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
    String label = "GST " + rate.stripTrailingZeros().toPlainString() + "% (incl.)";
    if (isWarehouseState(shipState)) {
      BigDecimal cgst = tax.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
      return new Pricing(
          sub, disc, shipping, total, tax, label, cgst, tax.subtract(cgst), null, coins, freeShipping);
    }
    return new Pricing(
        sub, disc, shipping, total, tax, label, null, null, tax, coins, freeShipping);
  }

  public BigDecimal shippingFee(BigDecimal afterDiscount, String currency) {
    OrderProperties.Shipping s = props.getShipping();
    boolean inr = isInr(currency);
    BigDecimal threshold = inr ? s.getInrFreeThreshold() : s.getOtherFreeThreshold();
    BigDecimal fee = inr ? s.getInrFee() : s.getOtherFee();
    return afterDiscount.compareTo(threshold) >= 0
        ? BigDecimal.ZERO.setScale(2)
        : fee.setScale(2, RoundingMode.HALF_UP);
  }

  /** Public delivery estimate. INR needs a valid Indian pincode (400 INVALID_PINCODE). */
  public DeliveryEstimateResponse estimate(String pincode, BigDecimal subtotal, String currency) {
    String cur = currency == null || currency.isBlank() ? "INR" : currency.toUpperCase(Locale.ROOT);
    String pin = pincode == null ? "" : pincode.trim();
    boolean indian = INDIAN_PINCODE.matcher(pin).matches();
    if (isInr(cur) && !indian) {
      throw new CodedException(ErrorCode.INVALID_PINCODE, "Enter a valid 6-digit Indian pincode");
    }
    if (pin.isEmpty() || pin.length() > 20) {
      throw new CodedException(ErrorCode.INVALID_PINCODE, "Enter a valid postal code");
    }
    int days;
    boolean cod;
    if (indian) {
      String prefix = props.getDelivery().getWarehousePincodePrefix();
      days = pin.startsWith(prefix) ? 2 : pin.charAt(0) == prefix.charAt(0) ? 3 : 5;
      cod = true;
    } else {
      days = 7;
      cod = false;
    }
    BigDecimal sub = subtotal == null ? BigDecimal.ZERO : subtotal;
    boolean inr = isInr(cur);
    BigDecimal threshold =
        inr ? props.getShipping().getInrFreeThreshold() : props.getShipping().getOtherFreeThreshold();
    return new DeliveryEstimateResponse(
        pin,
        true,
        days,
        today().plusDays(days),
        shippingFee(sub, cur),
        threshold,
        cur,
        cod);
  }

  /**
   * Delivery date from a specific warehouse: same 3-digit pincode prefix 2 days, same first digit
   * 3 days, else 5 (non-Indian destinations 7).
   */
  public LocalDate estimateDate(String shipPincode, String warehousePincode) {
    String pin = shipPincode == null ? "" : shipPincode.trim();
    int days;
    if (INDIAN_PINCODE.matcher(pin).matches()) {
      String wh =
          warehousePincode == null || warehousePincode.length() < 3
              ? props.getDelivery().getWarehousePincodePrefix()
              : warehousePincode;
      days = pin.regionMatches(0, wh, 0, 3) ? 2 : pin.charAt(0) == wh.charAt(0) ? 3 : 5;
    } else {
      days = 7;
    }
    return today().plusDays(days);
  }

  public LocalDate today() {
    return LocalDate.now(ZoneId.of(props.getDelivery().getZone()));
  }

  /** COD allowed: the pincode supports it and the total is within the per-currency limit. */
  public boolean codAllowed(DeliveryEstimateResponse estimate, BigDecimal total, String currency) {
    BigDecimal limit =
        isInr(currency) ? props.getDelivery().getCodLimitInr() : props.getDelivery().getCodLimitOther();
    return estimate.codAvailable() && total.compareTo(limit) <= 0;
  }

  private boolean isWarehouseState(String shipState) {
    if (shipState == null || shipState.isBlank()) {
      return false;
    }
    String s = shipState.trim();
    return props.getTax().getWarehouseStateAliases().stream().anyMatch(s::equalsIgnoreCase);
  }
}
