package com.acme.ecommerce.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.auth.admin.AdminUserDtos.TargetStatus;
import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.common.security.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Access-token revocation through auth's own resource server, with a real Redis: disable and demote
 * make an already-issued token 401, a token issued afterwards works, and the cutoff only moves
 * forward.
 */
class AccessTokenRevocationMvcTest extends AbstractAuthIntegrationTest {

  private static final String PASSWORD = "Password123!";

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AdminUserService admin;
  @Autowired private UserFixtures fixtures;
  @Autowired private DatabaseCleaner cleaner;
  @Autowired private StringRedisTemplate redis;

  private UserAccount actor;
  private UserAccount target;

  @BeforeEach
  void setUp() {
    cleaner.clean();
    actor = fixtures.createPasswordUser("actor@example.com", PASSWORD, Roles.ROLE_ADMIN);
    target = fixtures.createPasswordUser("target@example.com", PASSWORD, Roles.ROLE_ADMIN);
    // The first connection to the container can exceed the 500 ms command timeout; warm it up.
    for (int attempt = 1; ; attempt++) {
      try {
        redis.delete("access-deny:" + target.getId());
        break;
      } catch (RuntimeException e) {
        if (attempt == 10) {
          throw e;
        }
      }
    }
  }

  @Test
  @DisplayName("demote: the old token is 401 AUTH_TOKEN_INVALID, a later login works (AC-13..15)")
  void demoteRevokesOldTokenOnly() throws Exception {
    String before = loginToken();
    assertThat(me(before).getStatus()).isEqualTo(200);

    admin.changeRoles(actor.getId(), target.getId(), Set.of("VIEWER"));

    MockHttpServletResponse rejected = me(before);
    assertThat(rejected.getStatus()).isEqualTo(401);
    assertThat(json(rejected)).containsEntry("code", "AUTH_TOKEN_INVALID");

    Thread.sleep(1100); // iat has 1 s resolution: a fresh login must land after the cutoff
    String after = loginToken();
    assertThat(me(after).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("disable then enable: the pre-disable token stays dead, the cutoff only advances")
  void disableThenEnableKeepsOldTokenDead() throws Exception {
    String before = loginToken();

    admin.changeStatus(actor.getId(), target.getId(), TargetStatus.DISABLED);
    long first = cutoff();
    assertThat(me(before).getStatus()).isEqualTo(401);

    admin.changeStatus(actor.getId(), target.getId(), TargetStatus.ACTIVE);
    assertThat(cutoff()).isEqualTo(first); // enable never deletes or moves the entry
    assertThat(me(before).getStatus()).isEqualTo(401);

    Thread.sleep(1100);
    admin.changeRoles(actor.getId(), target.getId(), Set.of("VIEWER")); // demote after disable
    assertThat(cutoff()).isGreaterThanOrEqualTo(first);
  }

  private long cutoff() {
    return Long.parseLong(redis.opsForValue().get("access-deny:" + target.getId()));
  }

  private String loginToken() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequest("target@example.com", PASSWORD))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return (String) json(response).get("accessToken");
  }

  private MockHttpServletResponse me(String token) throws Exception {
    return mockMvc
        .perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andReturn()
        .getResponse();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return objectMapper.readValue(response.getContentAsString(), Map.class);
  }
}
