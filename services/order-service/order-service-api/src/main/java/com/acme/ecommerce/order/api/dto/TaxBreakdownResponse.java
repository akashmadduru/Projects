package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;

/** GST split: CGST+SGST for intra-state, IGST for inter-state; nulls when not applicable. */
public record TaxBreakdownResponse(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {}
