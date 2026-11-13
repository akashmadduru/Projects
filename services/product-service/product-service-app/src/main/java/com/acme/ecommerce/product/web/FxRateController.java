package com.acme.ecommerce.product.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Display-only FX rates: public {@code GET /api/products/fx-rates}, admin {@code PUT
 * /api/admin/fx-rates}. Checkout always charges in the product's own currency.
 */
@RestController
@RequiredArgsConstructor
public class FxRateController {

  private final JdbcTemplate jdbc;

  /** {@code rates} are units of each currency per one unit of {@code base}. */
  public record FxRatesPayload(String base, Map<String, BigDecimal> rates, Instant asOf) {}

  @GetMapping("/api/products/fx-rates")
  public ResponseEntity<FxRatesPayload> get() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
        .body(load());
  }

  @PutMapping("/api/admin/fx-rates")
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public FxRatesPayload put(@RequestBody FxRatesPayload body) {
    String base = body.base() == null ? "" : body.base().trim().toUpperCase();
    if (!base.matches("[A-Z]{3}")) {
      throw ValidationException.forField("base", "must be a 3-letter currency code");
    }
    if (body.rates() == null || body.rates().isEmpty()) {
      throw ValidationException.forField("rates", "at least one rate is required");
    }
    Map<String, BigDecimal> rates = new LinkedHashMap<>();
    body.rates()
        .forEach(
            (code, rate) -> {
              String c = code == null ? "" : code.trim().toUpperCase();
              if (!c.matches("[A-Z]{3}")) {
                throw ValidationException.forField("rates", "invalid currency code: " + code);
              }
              if (rate == null || rate.signum() <= 0) {
                throw ValidationException.forField("rates", "rate for " + c + " must be > 0");
              }
              rates.put(c, rate);
            });
    rates.putIfAbsent(base, BigDecimal.ONE);
    jdbc.update("DELETE FROM product.fx_rates");
    rates.forEach(
        (c, r) ->
            jdbc.update("INSERT INTO product.fx_rates (currency, base, rate) VALUES (?, ?, ?)", c, base, r));
    return load();
  }

  private FxRatesPayload load() {
    Map<String, BigDecimal> rates = new LinkedHashMap<>();
    String[] base = {"USD"};
    Instant[] asOf = {Instant.EPOCH};
    jdbc.query(
        "SELECT currency, base, rate, updated_at FROM product.fx_rates ORDER BY currency",
        rs -> {
          BigDecimal r = rs.getBigDecimal("rate").stripTrailingZeros();
          rates.put(rs.getString("currency"), r.scale() < 0 ? r.setScale(0) : r);
          base[0] = rs.getString("base");
          Timestamp ts = rs.getTimestamp("updated_at");
          if (ts != null && ts.toInstant().isAfter(asOf[0])) {
            asOf[0] = ts.toInstant();
          }
        });
    return new FxRatesPayload(base[0], rates, asOf[0]);
  }
}
