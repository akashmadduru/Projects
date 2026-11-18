package com.acme.ecommerce.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.ecommerce.user.membership.IpFingerprint;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
class ReferralEndpointsMvcTest extends AbstractUserIntegrationTest {

  private static final String SALT = "test-only-referral-salt";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcClient jdbc;
  @Autowired private IpFingerprint fingerprint;

  private static RequestPostProcessor as(UUID user) {
    return jwt().jwt(j -> j.subject(user.toString()));
  }

  private JsonNode view(UUID user, String xff) throws Exception {
    var req = get("/api/referrals").with(as(user));
    if (xff != null) {
      req = req.header("X-Forwarded-For", xff);
    }
    return objectMapper.readTree(
        mockMvc.perform(req).andReturn().getResponse().getContentAsString());
  }

  private MockHttpServletResponse claim(UUID user, String code, String xff) throws Exception {
    var req =
        post("/api/referrals/claim")
            .with(as(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}");
    if (xff != null) {
      req = req.header("X-Forwarded-For", xff);
    }
    return mockMvc.perform(req).andReturn().getResponse();
  }

  private void ageCode(String code) {
    jdbc.sql(
            "UPDATE users.referral_codes SET created_at = now() - interval '1 day' WHERE code = :c")
        .param("c", code)
        .update();
  }

  private List<String> hashes(UUID user) {
    return jdbc.sql("SELECT ip_hash FROM users.referral_ip_activity WHERE user_id = :u")
        .param("u", user)
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName("GET /api/referrals keeps exactly its pre-change fields")
  void responseShapeUnchanged() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/referrals")
                    .header("X-Forwarded-For", "203.0.113.50, 10.0.0.1")
                    .with(as(UUID.randomUUID())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode json = objectMapper.readTree(body);
    List<String> fields = new ArrayList<>();
    json.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("code", "link", "invited", "rewarded", "rewards");
    assertThat(body).doesNotContain("203.0.113.50");
  }

  @Test
  @DisplayName("AC-12: claiming without a JWT is 401")
  void claimWithoutJwtIs401() throws Exception {
    int status =
        mockMvc
            .perform(
                post("/api/referrals/claim")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"ABCDEFGH\"}"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isEqualTo(401);
    assertThat(mockMvc.perform(get("/api/referrals")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("AC-6 and AC-13: only the first X-Forwarded-For entry is recorded, as a hash")
  void recordsFirstForwardedEntry() throws Exception {
    UUID user = UUID.randomUUID();
    view(user, "203.0.113.50, 10.0.0.1, 10.0.0.2");
    assertThat(hashes(user))
        .containsExactly(fingerprint.hash(InetAddress.getByName("203.0.113.50")));
  }

  @Test
  @DisplayName("AC-17: refusals are the standard problem body with a generic detail")
  void refusalBodyIsGeneric() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = view(referrer, "203.0.113.50").get("code").asText();

    MockHttpServletResponse young = claim(UUID.randomUUID(), code, "198.51.100.77");
    assertThat(young.getStatus()).isEqualTo(422);
    JsonNode tooNew = objectMapper.readTree(young.getContentAsString());
    assertThat(tooNew.get("code").asText()).isEqualTo("REFERRAL_CODE_TOO_NEW");
    assertThat(tooNew.get("detail").asText()).isEqualTo("This referral code cannot be used yet");

    ageCode(code);
    MockHttpServletResponse same = claim(UUID.randomUUID(), code, "203.0.113.50");
    assertThat(same.getStatus()).isEqualTo(422);
    JsonNode ipMatch = objectMapper.readTree(same.getContentAsString());
    assertThat(ipMatch.get("code").asText()).isEqualTo("REFERRAL_IP_MATCH");
    assertThat(ipMatch.get("detail").asText())
        .isEqualTo("This referral code cannot be used from this network");

    for (JsonNode body : List.of(tooNew, ipMatch)) {
      List<String> fields = new ArrayList<>();
      body.fieldNames().forEachRemaining(fields::add);
      assertThat(fields)
          .doesNotContain("ip", "createdAt", "retryAfter", "minutes", "count", "ageSeconds");
      assertThat(body.toString()).doesNotContain("203.0.113.50").doesNotContain("198.51.100.77");
      assertThat(body.get("detail").asText()).doesNotContainPattern("\\d");
    }
  }

  @Test
  @DisplayName("an accepted claim is 204")
  void acceptedClaimIs204() throws Exception {
    String code = view(UUID.randomUUID(), "203.0.113.50").get("code").asText();
    ageCode(code);
    assertThat(claim(UUID.randomUUID(), code, "198.51.100.77").getStatus()).isEqualTo(204);
  }

  @Test
  @DisplayName("AC-14: malformed, blank and oversized X-Forwarded-For never cause a 5xx")
  void badForwardedHeadersAreNot5xx() throws Exception {
    String code = view(UUID.randomUUID(), null).get("code").asText();
    ageCode(code);
    for (String header :
        List.of("garbage", "   ", "example.com", ",,,", "x".repeat(5_000), "1.2.3.4:99999:1")) {
      assertThat(view(UUID.randomUUID(), header).has("code")).as(header.length() + "").isTrue();
      int status = claim(UUID.randomUUID(), code, header).getStatus();
      assertThat(status).as("claim with header of length " + header.length()).isLessThan(500);
    }
  }

  @Test
  @DisplayName("AC-5: no log line holds a raw IP or the salt across a view and claim flow")
  void noRawIpInLogs() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Level before = root.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    root.setLevel(Level.DEBUG);
    try {
      String referrerIp = "203.0.113.50";
      String refereeIp = "198.51.100.77";
      String code = view(UUID.randomUUID(), referrerIp).get("code").asText();
      claim(UUID.randomUUID(), code, refereeIp); // refused: too new
      ageCode(code);
      claim(UUID.randomUUID(), code, referrerIp); // refused: same network
      claim(UUID.randomUUID(), code, refereeIp); // accepted

      List<String> lines = new ArrayList<>();
      for (ILoggingEvent e : appender.list) {
        lines.add(e.getFormattedMessage());
        lines.add(
            String.valueOf(e.getArgumentArray() == null ? "" : List.of(e.getArgumentArray())));
        if (e.getThrowableProxy() != null) {
          lines.add(e.getThrowableProxy().getMessage());
        }
      }
      assertThat(appender.list).isNotEmpty();
      assertThat(String.join("\n", lines))
          .doesNotContain(referrerIp)
          .doesNotContain(refereeIp)
          .doesNotContain(SALT);
    } finally {
      root.detachAppender(appender);
      root.setLevel(before);
    }
  }
}
