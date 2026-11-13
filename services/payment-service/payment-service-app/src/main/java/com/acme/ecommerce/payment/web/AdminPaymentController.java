package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.payment.repository.AdminPaymentQueries;
import com.acme.ecommerce.payment.repository.AdminPaymentQueries.AdminPaymentRow;
import com.acme.ecommerce.payment.repository.AdminPaymentQueries.RefundRow;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only payment ledger for the admin console. Refunds are NOT issued here: they run through
 * the order-service return flow ({@code POST /api/admin/returns/{id}/status REFUNDED}), which keeps
 * order, stock and payment state consistent.
 */
@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','SUPPORT')")
public class AdminPaymentController {

  private static final int MAX_PAGE_SIZE = 50;

  /** Same JSON shape as Spring Data's {@code PagedModel}, which the SPA already parses. */
  public record Paged<T>(List<T> content, PageMeta page) {}

  public record PageMeta(int size, int number, long totalElements, int totalPages) {}

  public record PaymentDetail(AdminPaymentRow payment, List<RefundRow> refunds) {}

  private final AdminPaymentQueries queries;

  public AdminPaymentController(AdminPaymentQueries queries) {
    this.queries = queries;
  }

  @GetMapping
  public Paged<AdminPaymentRow> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    String st = upper(status);
    String m = upper(method);
    long total = queries.count(st, m, orderId);
    List<AdminPaymentRow> rows = queries.search(st, m, orderId, s, (long) p * s);
    int pages = (int) ((total + s - 1) / s);
    return new Paged<>(rows, new PageMeta(s, p, total, pages));
  }

  @GetMapping("/stats")
  public Map<String, Long> stats() {
    return queries.countByStatus();
  }

  @GetMapping("/{id}")
  public PaymentDetail get(@PathVariable UUID id) {
    AdminPaymentRow row =
        queries.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    return new PaymentDetail(row, queries.refunds(id));
  }

  private static String upper(String v) {
    return v == null || v.isBlank() ? null : v.trim().toUpperCase(Locale.ROOT);
  }
}
