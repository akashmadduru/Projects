package com.acme.ecommerce.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.api.dto.RegisterRequest;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.common.security.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract: cookies, status codes and problem documents.
 *
 * <p>Service-level behaviour is covered elsewhere. What this file exists to prove is the part only
 * reachable through the full servlet + security filter chain - that the refresh token is delivered
 * as an {@code HttpOnly} cookie and never in a body, that {@code /refresh} ignores every source
 * except that cookie, and that failures come back as RFC 9457 documents carrying the specific auth
 * code the SPA branches on.
 */
class AuthEndpointsIntegrationTest extends AbstractAuthIntegrationTest {

  private static final String EMAIL = "http-user@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String REFRESH_COOKIE = "refresh_token";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserFixtures userFixtures;
  @Autowired private DatabaseCleaner databaseCleaner;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    userFixtures.createPasswordUser(EMAIL, PASSWORD, Roles.ROLE_ADMIN);
  }

  @Test
  @DisplayName("login returns the access token in the body and the refresh token ONLY in a cookie")
  void loginSetsHttpOnlyRefreshCookie() throws Exception {
    MockHttpServletResponse response = login(EMAIL, PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
    Map<String, Object> body = json(response);
    assertThat(body).containsKeys("accessToken", "tokenType", "expiresIn");
    assertThat(body.get("tokenType")).isEqualTo("Bearer");
    // THE point: no refresh token anywhere in the JSON. If it were here the SPA
    // could read it, would store it, and an XSS would become a 14-day compromise
    // instead of a 10-minute one.
    assertThat(response.getContentAsString()).doesNotContain("refreshToken");

    String setCookie = setCookieHeader(response);
    assertThat(setCookie)
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/api/auth")
        .contains("Max-Age=");
  }

  @Test
  @DisplayName("refresh rotates the cookie, and replaying the OLD cookie is rejected")
  void refreshRotatesAndRejectsReplay() throws Exception {
    String firstCookie = cookieValue(login(EMAIL, PASSWORD));

    MockHttpServletResponse refreshed = refresh(firstCookie);
    assertThat(refreshed.getStatus()).isEqualTo(200);
    String secondCookie = cookieValue(refreshed);
    assertThat(secondCookie).isNotEqualTo(firstCookie);

    // The new cookie works.
    assertThat(refresh(secondCookie).getStatus()).isEqualTo(200);

    // The old one does not. Within the 10s grace window this is classified as a
    // RACE rather than as reuse - either way, a spent token is unusable.
    MockHttpServletResponse replay = refresh(firstCookie);
    assertThat(replay.getStatus()).isEqualTo(401);
    assertThat(json(replay).get("code")).isIn("AUTH_REFRESH_RACE", "AUTH_REFRESH_REUSE_DETECTED");
  }

  @Test
  @DisplayName("a race response does NOT clear the cookie; a hard rejection does")
  void raceKeepsTheCookieButInvalidClearsIt() throws Exception {
    String firstCookie = cookieValue(login(EMAIL, PASSWORD));
    refresh(firstCookie);

    // Race: the client's current cookie is the winner's valid child, so clearing
    // it here would log out a user who did nothing wrong.
    MockHttpServletResponse race = refresh(firstCookie);
    assertThat(json(race).get("code")).isEqualTo("AUTH_REFRESH_RACE");
    assertThat(race.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

    // Unknown token: nothing worth keeping, so the stale cookie is expired.
    MockHttpServletResponse invalid = refresh("definitely-not-a-token");
    assertThat(json(invalid).get("code")).isEqualTo("AUTH_REFRESH_INVALID");
    assertThat(setCookieHeader(invalid)).contains("Max-Age=0");
  }

  @Test
  @DisplayName("refresh with no cookie is 401 AUTH_REFRESH_INVALID as a problem document")
  void refreshWithoutCookieIsRejected() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(post("/api/auth/refresh")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(json(response))
        .containsEntry("code", "AUTH_REFRESH_INVALID")
        .containsKeys("type", "title", "status", "correlationId", "timestamp");
  }

  @Test
  @DisplayName("a refresh token offered in the BODY is ignored - the cookie is the only source")
  void refreshIgnoresRequestBody() throws Exception {
    String cookie = cookieValue(login(EMAIL, PASSWORD));

    // The request reaches the controller (no Authorization header, so the
    // resource-server filter does not intervene) and the controller finds no
    // cookie. Accepting the body field would make HttpOnly decorative: an XSS
    // that scraped the value from anywhere could then refresh at will.
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .header("X-Refresh-Token", cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("refreshToken", cookie))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(json(response)).containsEntry("code", "AUTH_REFRESH_INVALID");

    // And the token really is still unspent - the rejected call consumed nothing.
    assertThat(refresh(cookie).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a malformed Bearer token yields a problem document, not a bodiless 401")
  void malformedBearerTokenStillProducesProblemJson() throws Exception {
    String cookie = cookieValue(login(EMAIL, PASSWORD));

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer " + cookie))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    // Spring Security's resource-server DSL installs its own entry point, which
    // emits WWW-Authenticate and an EMPTY body. Overriding it matters because an
    // expired access token is the most common error the SPA will ever see, and a
    // bodiless 401 is indistinguishable from every other failure.
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(json(response))
        .containsEntry("code", "AUTH_TOKEN_INVALID")
        .containsKeys("type", "title", "status", "correlationId", "timestamp");
  }

  @Test
  @DisplayName("logout is 204 and idempotent, even with no cookie at all")
  void logoutIsIdempotent() throws Exception {
    String cookie = cookieValue(login(EMAIL, PASSWORD));

    assertThat(logout(cookie).getStatus()).isEqualTo(204);
    // Again with the same (now revoked) cookie, with nonsense, and with none:
    // a logout that can fail is worse than one that no-ops, because the user is
    // told it failed while their credential is still in the browser.
    assertThat(logout(cookie).getStatus()).isEqualTo(204);
    assertThat(logout("nonsense").getStatus()).isEqualTo(204);
    assertThat(logout(null).getStatus()).isEqualTo(204);

    // And the session really is gone.
    assertThat(refresh(cookie).getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("/me requires a bearer token and returns the caller's roles")
  @SuppressWarnings("unchecked")
  void meRequiresBearerToken() throws Exception {
    MockHttpServletResponse anonymous =
        mockMvc.perform(get("/api/auth/me")).andReturn().getResponse();
    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(json(anonymous)).containsEntry("code", "AUTH_TOKEN_INVALID");

    String accessToken = (String) json(login(EMAIL, PASSWORD)).get("accessToken");
    MockHttpServletResponse me =
        mockMvc
            .perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andReturn()
            .getResponse();

    assertThat(me.getStatus()).isEqualTo(200);
    assertThat(json(me)).containsEntry("email", EMAIL);
    // Unprefixed, matching the JWT `roles` claim.
    assertThat((List<String>) json(me).get("roles")).containsExactly("ADMIN");
  }

  @Test
  @DisplayName("/validate introspects the caller's own token")
  void validateIntrospectsToken() throws Exception {
    String accessToken = (String) json(login(EMAIL, PASSWORD)).get("accessToken");

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/validate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(json(response))
        .containsEntry("valid", true)
        .containsEntry("email", EMAIL)
        .containsEntry("authMethod", "PASSWORD");
  }

  @Test
  @DisplayName("registration is refused while auth.registration.enabled is false")
  void registrationIsDisabledByDefault() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "newbie@example.com", "a-long-enough-password", "Newbie"))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(json(response)).containsEntry("code", "AUTH_REGISTRATION_DISABLED");
  }

  @Test
  @DisplayName("bad credentials produce a problem document with the generic code and no leakage")
  void badCredentialsProduceProblemJson() throws Exception {
    MockHttpServletResponse response = login(EMAIL, "wrong-password");

    assertThat(response.getStatus()).isEqualTo(401);
    Map<String, Object> body = json(response);
    assertThat(body).containsEntry("code", "AUTH_INVALID_CREDENTIALS").containsEntry("status", 401);
    // No stack trace, and nothing that would confirm the address exists.
    assertThat(body).doesNotContainKeys("trace", "exception", "stackTrace");
    assertThat(response.getContentAsString()).doesNotContain(EMAIL);
  }

  @Test
  @DisplayName("an invalid request body is rejected by bean validation before any lookup")
  void malformedRequestIsRejected() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"not-an-email\",\"password\":\"x\"}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    // Translated by common-web's shared advice, so the envelope matches the rest
    // of the platform.
    assertThat(json(response)).containsEntry("code", "VALIDATION_FAILED");
  }

  @Test
  @DisplayName("an unknown PUBLIC route is a 404 problem document, not a 500")
  void unknownPublicRouteIs404() throws Exception {
    // Regression guard. Spring 6.1+ raises NoResourceFoundException (not the old
    // NoHandlerFoundException) for an unmatched path, so before common-web
    // learned that type every URL typo came back as a 500 - which on a dashboard
    // is indistinguishable from the service actually being broken.
    //
    // /oauth2/** is permitAll but has no handler when Google is unconfigured,
    // which is exactly the shape of the original bug.
    MockHttpServletResponse response =
        mockMvc.perform(get("/oauth2/authorization/google")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(json(response)).containsEntry("code", "RESOURCE_NOT_FOUND");
  }

  @Test
  @DisplayName("an unknown PROTECTED route is 401, and does not reveal whether it exists")
  void unknownProtectedRouteIs401() throws Exception {
    // Fail-closed: `anyRequest().authenticated()` rejects before routing, so a
    // caller cannot probe which admin endpoints exist by comparing 401 vs 404.
    MockHttpServletResponse response =
        mockMvc.perform(get("/api/auth/does-not-exist")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(json(response)).containsEntry("code", "AUTH_TOKEN_INVALID");
  }

  // --- helpers ---------------------------------------------------------------

  private MockHttpServletResponse login(String email, String password) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse refresh(String refreshCookie) throws Exception {
    return mockMvc
        .perform(withCookie(post("/api/auth/refresh"), refreshCookie))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse logout(String refreshCookie) throws Exception {
    return mockMvc
        .perform(withCookie(post("/api/auth/logout"), refreshCookie))
        .andReturn()
        .getResponse();
  }

  private static MockHttpServletRequestBuilder withCookie(
      MockHttpServletRequestBuilder builder, String refreshCookie) {
    return refreshCookie == null
        ? builder
        : builder.cookie(new Cookie(REFRESH_COOKIE, refreshCookie));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return objectMapper.readValue(response.getContentAsString(), Map.class);
  }

  private static String setCookieHeader(MockHttpServletResponse response) {
    return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
        .filter(header -> header.startsWith(REFRESH_COOKIE + "="))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No refresh_token cookie was set"));
  }

  private static String cookieValue(MockHttpServletResponse response) {
    String header = setCookieHeader(response).substring((REFRESH_COOKIE + "=").length());
    int end = header.indexOf(';');
    return end < 0 ? header : header.substring(0, end);
  }
}
