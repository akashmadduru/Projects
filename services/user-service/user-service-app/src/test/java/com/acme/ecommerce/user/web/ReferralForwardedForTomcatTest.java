package com.acme.ecommerce.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.net.ClientAddress;
import com.acme.ecommerce.user.membership.IpFingerprint;
import com.acme.ecommerce.user.membership.ReferralService;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Runs the real embedded Tomcat as on Kubernetes ({@code spring.main.cloud-platform=kubernetes}),
 * where Spring Boot would default {@code server.forward-headers-strategy} to NATIVE and Tomcat's
 * RemoteIpValve would rewrite X-Forwarded-For. The controller must ignore it and read only the
 * gateway header {@link ClientAddress#HEADER}; MockMvc never runs that valve, so only a real server
 * proves it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "spring.main.cloud-platform=kubernetes",
      "user.referral.min-code-age=0",
      "user.referral.ip-lookback=30d"
    })
class ReferralForwardedForTomcatTest extends AbstractUserIntegrationTest {

  private static final String SPOOFED = "198.51.100.66";
  private static final String REAL = "203.0.113.7";
  private static final String CHAIN = SPOOFED + ", 192.0.2.10, 192.0.2.20";

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private IpFingerprint fingerprint;
  @Autowired private ReferralService referrals;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void anyTokenIsTheUserId() {
    when(jwtDecoder.decode(anyString()))
        .thenAnswer(
            inv ->
                Jwt.withTokenValue(inv.getArgument(0))
                    .header("alg", "none")
                    .subject(inv.getArgument(0))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build());
  }

  private HttpResponse<String> call(String method, String path, UUID user, String body)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + user)
            .header(ClientAddress.HEADER, REAL)
            .header("X-Forwarded-For", CHAIN);
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private List<String> hashes(UUID user) {
    return jdbc.sql("SELECT ip_hash FROM users.referral_ip_activity WHERE user_id = :u")
        .param("u", user)
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName("on Kubernetes the gateway header address, not the X-Forwarded-For one, is recorded")
  void recordsTheSelectedEntry() throws Exception {
    UUID user = UUID.randomUUID();
    assertThat(call("GET", "/api/referrals", user, null).statusCode()).isEqualTo(200);

    assertThat(hashes(user))
        .containsExactly(fingerprint.hash(InetAddress.getByName(REAL)))
        .doesNotContain(fingerprint.hash(InetAddress.getByName(SPOOFED)));
  }

  @Test
  @DisplayName("a spoofed leftmost entry naming the referrer's network cannot cause a match")
  void spoofedEntryCannotMatch() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, InetAddress.getByName(SPOOFED)).code();

    UUID referee = UUID.randomUUID();
    HttpResponse<String> response =
        call("POST", "/api/referrals/claim", referee, "{\"code\":\"" + code + "\"}");

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(referrals.byReferee(referee)).isPresent();
  }
}
