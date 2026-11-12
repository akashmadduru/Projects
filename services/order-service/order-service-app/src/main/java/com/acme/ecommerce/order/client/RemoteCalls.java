package com.acme.ecommerce.order.client;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.error.OutOfStockException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Translates Feign failures of downstream services into this service's error model. */
public final class RemoteCalls {

  private static final Logger log = LoggerFactory.getLogger(RemoteCalls.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RemoteCalls() {}

  /**
   * A 4xx carrying a problem document with a known {@code code} is re-thrown as that code (so the
   * user sees COUPON_EXPIRED, OUT_OF_STOCK ...); anything else is a 503 DEPENDENCY_UNAVAILABLE.
   */
  public static RuntimeException translate(String service, FeignException e) {
    log.warn("Dependency {} failed: status={} {}", service, e.status(), e.getMessage());
    if (e.status() >= 400 && e.status() < 500) {
      try {
        JsonNode body = MAPPER.readTree(e.contentUTF8());
        String code = body.path("code").asText("");
        String detail = body.path("detail").asText("Request rejected by " + service);
        for (ErrorCode candidate : ErrorCode.values()) {
          if (candidate.code().equals(code) && candidate.status() < 500) {
            if (candidate == ErrorCode.OUT_OF_STOCK && body.has("items")) {
              List<Map<String, Object>> items = new ArrayList<>();
              for (JsonNode n : body.get("items")) {
                items.add(MAPPER.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
              }
              return new OutOfStockException(detail, items);
            }
            return new CodedException(candidate, detail);
          }
        }
      } catch (Exception parseFailure) {
        log.debug("Unparseable error body from {}", service);
      }
    }
    return new CodedException(
        ErrorCode.DEPENDENCY_UNAVAILABLE, service + " is unavailable", e);
  }

  /** Always a 503 (used where a 4xx from the dependency is not meaningful to the caller). */
  public static CodedException unavailable(String service, Exception e) {
    log.warn("Dependency {} failed: {}", service, e.toString());
    return new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, service + " is unavailable", e);
  }
}
