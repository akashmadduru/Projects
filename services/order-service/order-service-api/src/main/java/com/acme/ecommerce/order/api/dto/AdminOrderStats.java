package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Back-office dashboard figures. Revenue is net of refunds and excludes PENDING_PAYMENT and
 * CANCELLED orders; it is reported per currency because orders are never converted.
 */
public record AdminOrderStats(
    Instant generatedAt,
    Map<String, Long> ordersByStatus,
    Map<String, Long> returnsByStatus,
    List<CurrencyRevenue> revenueToday,
    List<CurrencyRevenue> revenueLast30Days) {

  public record CurrencyRevenue(String currency, BigDecimal amount, long orderCount) {}
}
