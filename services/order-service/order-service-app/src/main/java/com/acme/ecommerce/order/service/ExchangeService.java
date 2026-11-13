package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.PromotionFeignClient.ExchangeVerifyRequest;
import com.acme.ecommerce.order.client.PromotionFeignClient.ExchangeVerifyResponse;
import com.acme.ecommerce.order.client.RemoteCalls;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import feign.FeignException;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Exchange verification: promotion-service owns the quote, this service owns the order. Rejected or
 * short-valued devices leave a shortfall on the order; it is taken from the wallet when the balance
 * allows, else left flagged for support.
 */
@Service
public class ExchangeService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);

  public record VerifyResult(
      UUID quoteId,
      UUID orderId,
      String status,
      BigDecimal quotedValue,
      BigDecimal finalValue,
      BigDecimal shortfall,
      boolean shortfallCharged,
      boolean needsSupportFollowUp) {}

  private final PromotionFeignClient promotions;
  private final OrderStore store;
  private final OrderRemote remote;

  public ExchangeService(PromotionFeignClient promotions, OrderStore store, OrderRemote remote) {
    this.promotions = promotions;
    this.store = store;
    this.remote = remote;
  }

  public java.util.List<java.util.Map<String, Object>> list(String status) {
    try {
      return promotions.exchangeQuotes(status);
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  public VerifyResult verify(UUID quoteId, boolean accepted, BigDecimal finalValue) {
    ExchangeVerifyResponse r;
    try {
      r = promotions.verifyExchange(quoteId, new ExchangeVerifyRequest(accepted, finalValue));
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
    boolean charged = false;
    boolean followUp = false;
    if (r.shortfall().signum() > 0 && r.orderId() != null) {
      OrderResponse o = store.recordExchangeShortfall(r.orderId(), r.shortfall());
      try {
        remote.debitWalletKeyed(
            o.userId(), r.shortfall(), o.currency(), o.id(), "exchange-shortfall-" + quoteId);
        charged = true;
      } catch (RuntimeException e) {
        log.warn("Exchange shortfall {} for order {} not charged: {}", r.shortfall(), o.id(), e.toString());
        followUp = true;
      }
    }
    return new VerifyResult(
        quoteId, r.orderId(), r.status(), r.quotedValue(), r.finalValue(), r.shortfall(), charged, followUp);
  }

}
