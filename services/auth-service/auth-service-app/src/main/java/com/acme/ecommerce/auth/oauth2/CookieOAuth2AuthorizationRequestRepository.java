package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.token.JwtKeyProvider;
import com.acme.ecommerce.auth.web.CookieSupport;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Stores the in-flight OAuth2 authorization request in a signed cookie instead of the HTTP session.
 *
 * <p>WHY THIS EXISTS AT ALL. Spring Security's default is {@code
 * HttpSessionOAuth2AuthorizationRequestRepository}. It works perfectly with one instance and fails
 * the moment there are two: the browser starts the flow against replica A (which puts the state and
 * nonce in A's in-memory session), Google redirects back, the load balancer picks replica B, B
 * finds no saved request and rejects the callback with {@code authorization_request_not_found}. The
 * symptom is an intermittent "login sometimes fails" that disappears whenever anyone tries to
 * reproduce it on a single instance - and the usual field fix is to pin sessions, which quietly
 * makes the whole platform stateful. This service is designed to scale horizontally, so the flow
 * has to be stateless.
 *
 * <p>SECURITY OF THE COOKIE. The saved request carries the {@code state} and OIDC {@code nonce}
 * that defend the callback against CSRF and ID-token replay, so a cookie an attacker can forge
 * would defeat both. Two decisions follow:
 *
 * <ol>
 *   <li>the payload is HMAC-SHA256 signed and the signature is verified with a CONSTANT-TIME
 *       comparison before anything is parsed;
 *   <li>the payload is explicit JSON of the six fields actually needed - NOT Java serialization.
 *       Java-deserializing attacker-influenced bytes is a remote-code-execution class of bug even
 *       behind a MAC (one key leak turns into RCE rather than into forged logins), and the JSON
 *       form is also ~5x smaller, which matters against the 4KB cookie limit.
 * </ol>
 *
 * <p>The HMAC key is derived from the JWT signing key, which every replica already has. That avoids
 * introducing a second secret for operators to distribute - a secret that, being easy to forget,
 * would end up with a default value in a config file.
 *
 * <p>Lifetime is 3 minutes: long enough for a human to complete a Google consent screen, short
 * enough that a stolen cookie is worthless by the time it is found.
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  /** Cookie holding the signed authorization request. */
  public static final String AUTHORIZATION_REQUEST_COOKIE = "oauth2_auth_request";

  /** Cookie holding the SPA target to return to after login. */
  public static final String REDIRECT_TARGET_COOKIE = "oauth2_redirect_target";

  /** Query parameter a client may use to request a specific (allowlisted) return target. */
  public static final String REDIRECT_TARGET_PARAM = "redirect_uri";

  private static final Duration COOKIE_TTL = Duration.ofMinutes(3);
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String KEY_LABEL = "oauth2-authorization-request-cookie-v1";

  private static final Logger log =
      LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository.class);

  private final ObjectMapper objectMapper;
  private final RedirectTargetPolicy redirectTargets;
  private final AuthProperties.Cookie cookieConfig;
  private final byte[] hmacKey;

  public CookieOAuth2AuthorizationRequestRepository(
      ObjectMapper objectMapper,
      RedirectTargetPolicy redirectTargets,
      AuthProperties properties,
      JwtKeyProvider keyProvider) {
    this.objectMapper = objectMapper;
    this.redirectTargets = redirectTargets;
    this.cookieConfig = properties.cookie();

    String configured = properties.oauth2().stateCookieSecret();
    this.hmacKey =
        configured == null || configured.isBlank()
            ? keyProvider.deriveSecret(KEY_LABEL)
            : configured.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    return CookieSupport.read(request, AUTHORIZATION_REQUEST_COOKIE)
        .map(this::deserialise)
        .orElse(null);
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorizationRequest,
      HttpServletRequest request,
      HttpServletResponse response) {

    if (authorizationRequest == null) {
      removeAuthorizationRequest(request, response);
      return;
    }

    writeCookie(response, AUTHORIZATION_REQUEST_COOKIE, serialise(authorizationRequest), "/");

    // Captured HERE, at the start of the flow, because the callback from Google
    // carries only `code` and `state` - the client's original intent is gone by
    // then. Validated against the allowlist on the way in AND on the way out:
    // an unvalidated value written now is an open redirect later.
    String requested = request.getParameter(REDIRECT_TARGET_PARAM);
    String target = redirectTargets.resolve(requested);
    writeCookie(response, REDIRECT_TARGET_COOKIE, target, "/");
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
    clearCookie(response, AUTHORIZATION_REQUEST_COOKIE, "/");
    // NOTE: the redirect-target cookie is deliberately NOT cleared here. Spring
    // removes the authorization request BEFORE invoking the success handler, and
    // the handler still needs to know where to send the browser. Both success and
    // failure handlers clear it themselves.
    return authorizationRequest;
  }

  /** Reads (and validates) the post-login redirect target saved at the start of the flow. */
  public String readRedirectTarget(HttpServletRequest request) {
    return redirectTargets.resolve(
        CookieSupport.read(request, REDIRECT_TARGET_COOKIE).orElse(null));
  }

  /** Clears the redirect-target cookie. Called by the success and failure handlers. */
  public void clearRedirectTarget(HttpServletResponse response) {
    clearCookie(response, REDIRECT_TARGET_COOKIE, "/");
  }

  // --- serialisation ---------------------------------------------------------

  /**
   * The subset of {@link OAuth2AuthorizationRequest} needed to complete the flow.
   *
   * <p>{@code authorizationRequestUri} is intentionally absent: the builder recomputes it from
   * these fields, and storing it would double the cookie size for no benefit.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SavedRequest(
      String authorizationUri,
      String clientId,
      String redirectUri,
      Set<String> scopes,
      String state,
      Map<String, Object> additionalParameters,
      Map<String, Object> attributes) {}

  private String serialise(OAuth2AuthorizationRequest authorizationRequest) {
    SavedRequest saved =
        new SavedRequest(
            authorizationRequest.getAuthorizationUri(),
            authorizationRequest.getClientId(),
            authorizationRequest.getRedirectUri(),
            authorizationRequest.getScopes(),
            authorizationRequest.getState(),
            stringify(authorizationRequest.getAdditionalParameters()),
            stringify(authorizationRequest.getAttributes()));
    try {
      byte[] payload = objectMapper.writeValueAsBytes(saved);
      return encoder().encodeToString(payload) + "." + encoder().encodeToString(sign(payload));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialise the OAuth2 authorization request", e);
    }
  }

  private OAuth2AuthorizationRequest deserialise(String cookieValue) {
    int separator = cookieValue.lastIndexOf('.');
    if (separator <= 0) {
      log.warn("Malformed OAuth2 authorization-request cookie: no signature separator");
      return null;
    }
    try {
      byte[] payload = decoder().decode(cookieValue.substring(0, separator));
      byte[] signature = decoder().decode(cookieValue.substring(separator + 1));

      // Constant-time: a byte-by-byte comparison that returns early leaks the
      // expected MAC one byte at a time to an attacker who can time it.
      if (!MessageDigest.isEqual(sign(payload), signature)) {
        log.warn("Rejected OAuth2 authorization-request cookie with an invalid signature");
        return null;
      }

      SavedRequest saved = objectMapper.readValue(payload, SavedRequest.class);
      return OAuth2AuthorizationRequest.authorizationCode()
          .authorizationUri(saved.authorizationUri())
          .clientId(saved.clientId())
          .redirectUri(saved.redirectUri())
          .scopes(saved.scopes())
          .state(saved.state())
          .additionalParameters(saved.additionalParameters())
          .attributes(saved.attributes())
          .build();
    } catch (IllegalArgumentException | java.io.IOException e) {
      // Truncated, tampered or simply stale cookie. Returning null makes Spring
      // Security fail the callback with authorization_request_not_found, which is
      // exactly the right outcome; the exception message is not echoed anywhere.
      log.warn("Unreadable OAuth2 authorization-request cookie: {}", e.getMessage());
      return null;
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
      return mac.doFinal(payload);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Unable to sign the OAuth2 authorization request", e);
    }
  }

  private static Map<String, Object> stringify(Map<String, Object> source) {
    // Values here are Strings in every flow Spring Security produces; coercing
    // makes the JSON round-trip total rather than dependent on Jackson's
    // polymorphic handling of Object values (which is off by default, for good
    // reason).
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, value == null ? null : value.toString()));
    return copy;
  }

  private void writeCookie(HttpServletResponse response, String name, String value, String path) {
    CookieSupport.write(
        response,
        name,
        value,
        path,
        COOKIE_TTL,
        cookieConfig.secure(),
        // Lax, not Strict: the cookie must survive Google's cross-site redirect
        // back to the callback endpoint. Strict would drop it and every login
        // would fail at the last step.
        "Lax",
        cookieConfig.domain());
  }

  private void clearCookie(HttpServletResponse response, String name, String path) {
    CookieSupport.clear(response, name, path, cookieConfig.secure(), "Lax", cookieConfig.domain());
  }

  private static Base64.Encoder encoder() {
    return Base64.getUrlEncoder().withoutPadding();
  }

  private static Base64.Decoder decoder() {
    return Base64.getUrlDecoder();
  }
}
