package com.acme.ecommerce.auth.privacy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 10 privacy: consumes USER_DELETED from {@code user.events} (raw JSON string, so a payload
 * change cannot poison the topic) and anonymises this service's data for that user. Statements
 * are idempotent; redelivery is harmless.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class UserDeletedListener {

  private static final Logger log = LoggerFactory.getLogger(UserDeletedListener.class);
  private static final Pattern USER_ID =
      Pattern.compile("\"userId\" *: *\"([0-9a-fA-F-]{36})\"");

  /** SQL with :u, run in order. */
  private static final String[] ANONYMISE = {
    "UPDATE auth.users SET email = 'deleted-' || id || '@invalid', display_name = 'Deleted user', phone = NULL, phone_verified = false, email_verified = false, status = 'DISABLED', updated_at = now() WHERE id = :u",
    "DELETE FROM auth.user_credentials WHERE user_id = :u",
    "DELETE FROM auth.federated_identities WHERE user_id = :u",
    "UPDATE auth.refresh_tokens SET revoked_at = coalesce(revoked_at, now()), revoked_reason = coalesce(revoked_reason, 'ADMIN'), user_agent = NULL, ip_address = NULL WHERE user_id = :u",
    "DELETE FROM auth.one_time_tokens WHERE user_id = :u",
    "DELETE FROM auth.user_totp WHERE user_id = :u",
    "DELETE FROM auth.recovery_codes WHERE user_id = :u",
    "DELETE FROM auth.mfa_challenges WHERE user_id = :u",
    "DELETE FROM auth.known_devices WHERE user_id = :u",
    "DELETE FROM auth.user_roles WHERE user_id = :u"
  };

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> privacyUserEventsListenerFactory(
      @Value("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}") String servers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    ConcurrentKafkaListenerContainerFactory<String, String> f =
        new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(props, StringDeserializer::new, StringDeserializer::new));
    f.setCommonErrorHandler(com.acme.ecommerce.common.web.kafka.DlqErrorHandlers.create(servers));
    return f;
  }

  @Component
  public static class Listener {
    private final JdbcClient jdbc;

    public Listener(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @KafkaListener(
        topics = "user.events",
        groupId = "auth-service-privacy",
        containerFactory = "privacyUserEventsListenerFactory")
    public void onEvent(String payload) {
      if (payload == null || !payload.contains("\"USER_DELETED\"")) {
        return;
      }
      Matcher m = USER_ID.matcher(payload);
      if (!m.find()) {
        return;
      }
      UUID userId = UUID.fromString(m.group(1));
      for (String sql : ANONYMISE) {
        jdbc.sql(sql).param("u", userId).update();
      }
      log.info("Anonymised data of deleted user {}", userId);
    }
  }
}
