package com.acme.ecommerce.order.domain;

import java.math.BigDecimal;

/** Computed money breakdown for an order (all tax-inclusive; tax is the portion inside total). */
public record Pricing(
    BigDecimal subtotal,
    BigDecimal discount,
    BigDecimal shipping,
    BigDecimal total,
    BigDecimal tax,
    String taxLabel,
    BigDecimal cgst,
    BigDecimal sgst,
    BigDecimal igst) {}
