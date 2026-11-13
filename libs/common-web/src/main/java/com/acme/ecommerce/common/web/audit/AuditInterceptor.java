package com.acme.ecommerce.common.web.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Audits every successful back-office write without touching each controller: non-GET requests
 * under {@code /api/admin/**} and {@code /api/seller/**}, plus catalog/inventory writes by an
 * ADMIN or CATALOG_MANAGER. Runs in {@code afterCompletion}, i.e. after the service-layer
 * transaction committed. Customer traffic (carts, orders, payments by the account owner) is never
 * audited here.
 */
public class AuditInterceptor implements HandlerInterceptor {

  private static final Set<String> STAFF = Set.of("ADMIN", "CATALOG_MANAGER", "SUPPORT", "FINANCE", "SELLER");
  private static final Set<String> CATALOG_ROLES = Set.of("ADMIN", "CATALOG_MANAGER");
  private static final List<String> CATALOG_PREFIXES =
      List.of("/api/products", "/api/categories", "/api/inventory", "/api/sellers");

  private final AuditPublisher publisher;

  public AuditInterceptor(AuditPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    try {
      String method = request.getMethod();
      if ("GET".equals(method)
          || "HEAD".equals(method)
          || "OPTIONS".equals(method)
          || response.getStatus() >= 400) {
        return;
      }
      String path = request.getRequestURI();
      boolean backOffice = path.startsWith("/api/admin/") || path.startsWith("/api/seller/");
      boolean catalog = CATALOG_PREFIXES.stream().anyMatch(path::startsWith);
      if (!backOffice && !catalog) {
        return;
      }
      AuditPublisher.Actor actor = AuditPublisher.currentActor();
      if (actor.userId() == null || actor.roles().stream().noneMatch(STAFF::contains)) {
        return;
      }
      if (!backOffice && actor.roles().stream().noneMatch(CATALOG_ROLES::contains)) {
        return;
      }
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      String route = pattern == null ? path : pattern.toString();
      Map<String, String> vars =
          (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      String resourceId = vars == null || vars.isEmpty() ? null : vars.values().iterator().next();
      String[] seg = route.split("/");
      String resourceType = null;
      for (String s : seg) {
        if (s.isEmpty() || s.equals("api") || s.equals("admin") || s.equals("seller") || s.startsWith("{")) {
          continue;
        }
        resourceType = s.toUpperCase(java.util.Locale.ROOT);
        break;
      }
      String after =
          "{\"method\":"
              + AuditPublisher.q(method)
              + ",\"path\":"
              + AuditPublisher.q(path)
              + ",\"status\":"
              + response.getStatus()
              + "}";
      publisher.publishNow(actor, method + " " + route, resourceType, resourceId, after, ip(request));
    } catch (RuntimeException e) {
      // fail-open: never let auditing affect the response
    }
  }

  private static String ip(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
