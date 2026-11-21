package com.acme.ecommerce.user.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.common.security.PlatformJwtAuthenticationConverter;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** AC-8: the admin outbox endpoints; C1 role mapping goes through the real converter. */
@AutoConfigureMockMvc
class UserOutboxAdminMvcTest extends AbstractUserIntegrationTest {

  private static final String BASE = "/api/users/admin/outbox";

  @MockitoBean(name = "privacyKafkaTemplate")
  private KafkaTemplate<String, String> kafka;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcClient jdbc;
  @Autowired private UserOutboxRelay relay;

  @BeforeEach
  void clean() {
    reset(kafka);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    jdbc.sql("DELETE FROM users.outbox").update();
  }

  /**
   * A token as auth-service issues it: a {@code roles} claim, converted by the platform converter.
   */
  private static RequestPostProcessor withRole(String role) {
    Jwt token =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject(UUID.randomUUID().toString())
            .claim("roles", List.of(role))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    return authentication(new PlatformJwtAuthenticationConverter().convert(token));
  }

  private UUID insert(String status, int attempts) {
    UUID user = UUID.randomUUID();
    return jdbc.sql(
            "INSERT INTO users.outbox (aggregate_id, kind, payload, idem_key, status, attempts,"
                + " last_error) VALUES (:u, 'USER_DELETED', CAST(:p AS jsonb), :i, :s, :n, 'boom')"
                + " RETURNING id")
        .param("u", user)
        .param(
            "p",
            UserOutbox.toJson(
                new UserOutbox.UserDeletedEvent("e", "USER_DELETED", user.toString(), "t")))
        .param("i", "USER_DELETED:" + user)
        .param("s", status)
        .param("n", attempts)
        .query(UUID.class)
        .single();
  }

  @Test
  @DisplayName("the platform converter maps the roles claim ADMIN to ROLE_ADMIN")
  void converterMapsAdmin() {
    Jwt token =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("s")
            .claim("roles", List.of("ADMIN"))
            .build();
    var auth = new PlatformJwtAuthenticationConverter().convert(token);
    assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_ADMIN");
  }

  @Test
  @DisplayName("GET: ADMIN 200 without payload, CUSTOMER 403, anonymous 401")
  void listSecurity() throws Exception {
    insert("DEAD", 20);
    String body =
        mockMvc
            .perform(get(BASE).with(withRole("ADMIN")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode first = objectMapper.readTree(body).get(0);
    assertThat(first.get("attempts").asInt()).isEqualTo(20);
    assertThat(first.get("lastError").asText()).isEqualTo("boom");
    assertThat(first.get("kind").asText()).isEqualTo("USER_DELETED");
    assertThat(first.has("payload")).isFalse();
    assertThat(body).doesNotContain("eventId");

    assertThat(
            mockMvc
                .perform(get(BASE).with(withRole("CUSTOMER")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(mockMvc.perform(get(BASE)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("POST retry: ADMIN 200, CUSTOMER 403, anonymous 401")
  void retrySecurity() throws Exception {
    UUID id = insert("DEAD", 20);
    String url = BASE + "/" + id + "/retry";
    assertThat(
            mockMvc
                .perform(post(url).with(withRole("CUSTOMER")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(mockMvc.perform(post(url)).andReturn().getResponse().getStatus()).isEqualTo(401);
    assertThat(
            mockMvc
                .perform(post(url).with(withRole("ADMIN")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("GET is capped at 200 rows")
  void capped() throws Exception {
    jdbc.sql(
            "INSERT INTO users.outbox (aggregate_id, kind, idem_key, status) SELECT"
                + " gen_random_uuid(), 'USER_DELETED', 'cap-' || g, 'DEAD' FROM"
                + " generate_series(1, 205) g")
        .update();
    JsonNode rows =
        objectMapper.readTree(
            mockMvc
                .perform(get(BASE).with(withRole("ADMIN")))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(rows).hasSize(200);
  }

  @Test
  @DisplayName("retry puts a DEAD row back to PENDING and the relay then delivers it")
  void retryThenDeliver() throws Exception {
    UUID id = insert("DEAD", 20);
    assertThat(
            mockMvc
                .perform(post(BASE + "/" + id + "/retry").with(withRole("ADMIN")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(statusOf(id)).isEqualTo("PENDING");
    relay.relay();
    assertThat(statusOf(id)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("retry on a PENDING row or an unknown id is 404")
  void retryNotFound() throws Exception {
    UUID pending = insert("PENDING", 0);
    for (UUID id : List.of(pending, UUID.randomUUID())) {
      assertThat(
              mockMvc
                  .perform(post(BASE + "/" + id + "/retry").with(withRole("ADMIN")))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(404);
    }
  }

  private String statusOf(UUID id) {
    return jdbc.sql("SELECT status FROM users.outbox WHERE id = :i")
        .param("i", id)
        .query(String.class)
        .single();
  }
}
