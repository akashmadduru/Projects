package com.acme.ecommerce.notification.inbox;

import com.acme.ecommerce.notification.client.UserFeignClient;
import com.acme.ecommerce.notification.client.UserFeignClient.Preferences;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** user-service preferences with a 60 s in-memory cache; defaults on failure (fail-open). */
@Component
public class PreferenceLookup {

  private static final Logger log = LoggerFactory.getLogger(PreferenceLookup.class);
  public static final Preferences DEFAULTS = new Preferences(true, true, true, false, true);
  private static final long TTL_MS = 60_000;
  private static final int MAX = 5000;

  private record Entry(Preferences prefs, long at) {}

  private final UserFeignClient users;
  private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

  public PreferenceLookup(UserFeignClient users) {
    this.users = users;
  }

  public Preferences get(UUID userId) {
    Entry e = cache.get(userId);
    long now = System.currentTimeMillis();
    if (e != null && now - e.at() < TTL_MS) {
      return e.prefs();
    }
    try {
      Preferences p = users.preferences(userId);
      if (cache.size() > MAX) {
        cache.clear();
      }
      cache.put(userId, new Entry(p, now));
      return p;
    } catch (RuntimeException ex) {
      log.warn("Preferences lookup failed for {}, using defaults: {}", userId, ex.toString());
      return DEFAULTS;
    }
  }
}
