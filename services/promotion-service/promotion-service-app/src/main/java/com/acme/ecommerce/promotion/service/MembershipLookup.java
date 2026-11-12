package com.acme.ecommerce.promotion.service;

import com.acme.ecommerce.promotion.client.UserFeignClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Best-effort membership check: any failure means "not a member". */
@Component
public class MembershipLookup {

  private static final Logger log = LoggerFactory.getLogger(MembershipLookup.class);

  private final UserFeignClient users;

  public MembershipLookup(UserFeignClient users) {
    this.users = users;
  }

  public boolean isMember(UUID userId) {
    if (userId == null) {
      return false;
    }
    try {
      UserFeignClient.Membership n = users.membership(userId);
      return n != null && n.active();
    } catch (RuntimeException e) {
      log.warn("Membership lookup failed for {}: {}", userId, e.toString());
      return false;
    }
  }
}
