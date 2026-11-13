package com.acme.ecommerce.support.audit;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.support.Paged;
import com.acme.ecommerce.support.audit.AuditService.Entry;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin audit log (ADMIN only). */
@RestController
public class AuditController {

  private final AuditService audit;

  public AuditController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping("/api/admin/audit")
  @PreAuthorize("hasRole('ADMIN')")
  public Paged<Entry> search(
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) String resourceId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return audit.search(actor, resourceType, resourceId, action, instant(from, "from"), instant(to, "to"), page, size);
  }

  private static Instant instant(String s, String field) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return s.length() == 10 ? Instant.parse(s + "T00:00:00Z") : Instant.parse(s);
    } catch (DateTimeParseException e) {
      throw ValidationException.forField(field, "Use ISO-8601, e.g. 2026-01-31 or 2026-01-31T10:00:00Z");
    }
  }
}
