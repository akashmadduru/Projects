package com.acme.ecommerce.auth.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Local, fail-open publisher for {@code audit.events} (JSON string payload, shape per the Phase 10
 * contract). TODO(p10-auth): replace with libs/common-web AuditPublisher once available; kept local
 * because auth-service had no Kafka wiring and the shared helper did not exist yet.
 */
@Component
public class AuthAuditPublisher {

  private static final Logger log = LoggerFactory.getLogger(AuthAuditPublisher.class);
  private static final String TOPIC = "audit.events";

  private final ObjectProvider<KafkaTemplate<String, String>> kafka;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public AuthAuditPublisher(ObjectProvider<KafkaTemplate<String, String>> kafka) {
    this.kafka = kafka;
  }

  /** {@code beforeJson}/{@code afterJson} must already be valid small JSON (or null). */
  public void publish(
      Jwt actor,
      String ip,
      String action,
      String resourceType,
      String resourceId,
      String beforeJson,
      String afterJson) {
    try {
      List<String> roles = actor == null ? null : actor.getClaimAsStringList("roles");
      StringBuilder sb = new StringBuilder("{");
      sb.append("\"eventId\":\"").append(UUID.randomUUID()).append("\",");
      sb.append("\"actorUserId\":").append(str(actor == null ? null : actor.getSubject())).append(',');
      sb.append("\"actorRoles\":[");
      if (roles != null) {
        for (int i = 0; i < roles.size(); i++) {
          sb.append(i > 0 ? "," : "").append(str(roles.get(i)));
        }
      }
      sb.append("],");
      sb.append("\"action\":").append(str(action)).append(',');
      sb.append("\"resourceType\":").append(str(resourceType)).append(',');
      sb.append("\"resourceId\":").append(str(resourceId)).append(',');
      sb.append("\"before\":").append(beforeJson == null ? "null" : beforeJson).append(',');
      sb.append("\"after\":").append(afterJson == null ? "null" : afterJson).append(',');
      sb.append("\"ip\":").append(str(ip)).append(',');
      sb.append("\"at\":\"").append(Instant.now()).append("\"}");
      String payload = sb.toString();
      executor.execute(
          () -> {
            try {
              KafkaTemplate<String, String> template = kafka.getIfAvailable();
              if (template != null) {
                template.send(TOPIC, resourceId, payload);
              }
            } catch (RuntimeException e) {
              log.warn("Audit publish failed ({}): {}", action, e.toString());
            }
          });
    } catch (RuntimeException e) {
      log.warn("Audit publish failed ({}): {}", action, e.toString());
    }
  }

  public static String str(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c < 0x20 ? " " : String.valueOf(c));
      }
    }
    return sb.append('"').toString();
  }

  public static String strArray(java.util.Collection<String> values) {
    StringBuilder sb = new StringBuilder("[");
    int i = 0;
    for (String v : values) {
      sb.append(i++ > 0 ? "," : "").append(str(v));
    }
    return sb.append(']').toString();
  }
}
