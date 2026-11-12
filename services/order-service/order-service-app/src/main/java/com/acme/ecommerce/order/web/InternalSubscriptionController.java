package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.service.SubscriptionService;
import com.acme.ecommerce.order.service.SubscriptionService.RunSummary;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal (X-Internal-Token): subscription job trigger and last-order lookup. */
@RestController
public class InternalSubscriptionController {

  private final SubscriptionService subscriptions;
  private final OrderRepository orders;

  public InternalSubscriptionController(SubscriptionService subscriptions, OrderRepository orders) {
    this.subscriptions = subscriptions;
    this.orders = orders;
  }

  @PostMapping("/internal/subscriptions/run-due")
  public RunSummary runDue(@RequestParam(required = false) LocalDate date) {
    return subscriptions.runDue(date != null ? date : LocalDate.now());
  }

  /** userId to ISO instant of the user's latest non-cancelled order (users without one omitted). */
  @GetMapping("/internal/orders/last-order-dates")
  @Transactional(readOnly = true)
  public Map<String, String> lastOrderDates(@RequestParam("userIds") List<UUID> userIds) {
    Map<String, String> out = new LinkedHashMap<>();
    if (userIds.isEmpty()) {
      return out;
    }
    for (Object[] r : orders.lastOrderDates(userIds.stream().limit(1000).toList())) {
      out.put(String.valueOf(r[0]), String.valueOf(Time.instant(r[1])));
    }
    return out;
  }
}
