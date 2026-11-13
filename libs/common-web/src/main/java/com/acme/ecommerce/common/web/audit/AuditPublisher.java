package com.acme.ecommerce.common.web.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Publishes admin/seller write audit events to {@code audit.events}. FAIL-OPEN by design: an
 * unreachable broker or a serialisation problem is logged and swallowed - auditing must never break
 * the business write. Events are sent after the write finished: {@link AuditInterceptor} calls this
 * from {@code afterCompletion} (the service-layer transaction has already committed), and callers
 * inside an open transaction get an {@code afterCommit} hook.
 *
 * <p>Payload (JSON): eventId, actorUserId, actorRoles[], action, resourceType, resourceId, before,
 * after, ip, at, service. Owns a tiny plain Kafka producer (String serialisers) so it works in any
 * service regardless of that service's own KafkaTemplate value type.
 */
public class AuditPublisher implements AutoCloseable {

  public static final String TOPIC = "audit.events";
  private static final Logger log = LoggerFactory.getLogger(AuditPublisher.class);

  private final String bootstrapServers;
  private final String service;
  private volatile KafkaProducer<String, String> producer;

  public AuditPublisher(String bootstrapServers, String service) {
    this.bootstrapServers = bootstrapServers;
    this.service = service;
  }

  /** Explicit publish for callers that know before/after (small JSON strings, may be null). */
  public void publish(
      String action, String resourceType, String resourceId, String beforeJson, String afterJson, String ip) {
    try {
      Actor actor = currentActor();
      String json = toJson(actor, action, resourceType, resourceId, beforeJson, afterJson, ip);
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isSynchronizationActive()) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                send(resourceId, json);
              }
            });
      } else {
        send(resourceId, json);
      }
    } catch (Throwable t) {
      log.warn("Audit event dropped (fail-open): {}", t.toString());
    }
  }

  /** Used by the interceptor, which already knows the actor and runs after the commit. */
  void publishNow(
      Actor actor, String action, String resourceType, String resourceId, String afterJson, String ip) {
    try {
      send(resourceId, toJson(actor, action, resourceType, resourceId, null, afterJson, ip));
    } catch (Throwable t) {
      log.warn("Audit event dropped (fail-open): {}", t.toString());
    }
  }

  private void send(String key, String json) {
    try {
      producer().send(
              new ProducerRecord<>(TOPIC, key == null ? UUID.randomUUID().toString() : key, json),
              (md, ex) -> {
                if (ex != null) {
                  log.warn("Audit event not delivered (fail-open): {}", ex.toString());
                }
              });
    } catch (Throwable t) {
      log.warn("Audit event not sent (fail-open): {}", t.toString());
    }
  }

  private KafkaProducer<String, String> producer() {
    KafkaProducer<String, String> p = producer;
    if (p == null) {
      synchronized (this) {
        if (producer == null) {
          Properties props = new Properties();
          props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
          props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
          props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
          props.put(ProducerConfig.ACKS_CONFIG, "1");
          props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
          props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
          props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "6000");
          props.put(ProducerConfig.CLIENT_ID_CONFIG, service + "-audit");
          producer = new KafkaProducer<>(props);
        }
        p = producer;
      }
    }
    return p;
  }

  record Actor(String userId, List<String> roles) {}

  static Actor currentActor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
      return new Actor(null, List.of());
    }
    List<String> roles = new ArrayList<>();
    for (GrantedAuthority a : auth.getAuthorities()) {
      String r = a.getAuthority();
      roles.add(r != null && r.startsWith("ROLE_") ? r.substring(5) : r);
    }
    return new Actor(auth.getName(), roles);
  }

  private String toJson(
      Actor actor,
      String action,
      String resourceType,
      String resourceId,
      String before,
      String after,
      String ip) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("{\"eventId\":").append(q(UUID.randomUUID().toString()));
    sb.append(",\"actorUserId\":").append(q(actor.userId()));
    sb.append(",\"actorRoles\":[");
    for (int i = 0; i < actor.roles().size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(q(actor.roles().get(i)));
    }
    sb.append("],\"action\":").append(q(action));
    sb.append(",\"resourceType\":").append(q(resourceType));
    sb.append(",\"resourceId\":").append(q(resourceId));
    sb.append(",\"before\":").append(before == null ? "null" : before);
    sb.append(",\"after\":").append(after == null ? "null" : after);
    sb.append(",\"ip\":").append(q(ip));
    sb.append(",\"at\":").append(q(Instant.now().toString()));
    sb.append(",\"service\":").append(q(service)).append('}');
    return sb.toString();
  }

  static String q(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  @Override
  public void close() {
    KafkaProducer<String, String> p = producer;
    if (p != null) {
      try {
        p.close(java.time.Duration.ofSeconds(2));
      } catch (RuntimeException e) {
        log.debug("Audit producer close failed: {}", e.toString());
      }
    }
  }
}
