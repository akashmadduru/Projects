package com.acme.ecommerce.common.web.kafka;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * The one place the platform builds Kafka JSON (de)serializers.
 *
 * <p>spring-kafka 4 deprecated {@code JsonSerializer}/{@code JsonDeserializer} for removal in
 * favour of the Jackson 3 versions. The platform deliberately stays on Jackson 2 for now (event
 * payloads and their annotations are Jackson 2), so the deprecated classes are used here and only
 * here. Moving to Jackson 3 is a change to this file (tracked in docs/techdebt/phase6.md).
 *
 * <p>Both tokens are needed: javac reports these classes as deprecated for removal, the IDE's
 * Eclipse compiler as plain deprecation (it is told not to flag the token it does not use, see
 * .vscode/eclipse-compiler.prefs).
 */
@SuppressWarnings({"deprecation", "removal"})
public final class KafkaJson {

  private KafkaJson() {}

  /**
   * A consumer value deserializer for {@code type}: type headers are ignored (producers do not send
   * them), and a record that cannot be read is handed to the error handler instead of crashing the
   * container, so it reaches the dead-letter topic with its original bytes.
   *
   * @param trustedPackages packages a payload may name; "*" trusts any (type headers are off)
   */
  public static <T> Deserializer<T> eventDeserializer(Class<T> type, String... trustedPackages) {
    JsonDeserializer<T> json = new JsonDeserializer<>(type, false);
    json.addTrustedPackages(trustedPackages.length == 0 ? new String[] {"*"} : trustedPackages);
    return new ErrorHandlingDeserializer<>(json);
  }

  /** A value serializer for arbitrary payloads (dead-letter records), without type headers. */
  public static Serializer<Object> payloadSerializer() {
    JsonSerializer<Object> json = new JsonSerializer<>();
    json.setAddTypeInfo(false);
    return json;
  }
}
