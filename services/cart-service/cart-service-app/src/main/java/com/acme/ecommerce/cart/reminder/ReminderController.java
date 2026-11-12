package com.acme.ecommerce.cart.reminder;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dev/ops trigger (X-Internal-Token via InternalSecurityConfig; never routed by the gateway). */
@RestController
public class ReminderController {

  private final ReminderService reminders;

  public ReminderController(ReminderService reminders) {
    this.reminders = reminders;
  }

  @PostMapping("/internal/cart/reminders/run")
  public Map<String, Integer> run(
      @RequestParam(defaultValue = "240") int minAgeMinutes) {
    return Map.of("sent", reminders.run(Math.max(minAgeMinutes, 0)));
  }
}
