package com.acme.ecommerce.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable this service has, in one type-safe, IDE-completable place.
 *
 * <p>Records with constructor binding rather than a mutable class: the settings are read at startup
 * and never change, so making them immutable removes a whole class of "who mutated the token TTL at
 * runtime" question. Every nested section carries {@link DefaultValue} annotations so that omitting
 * a section entirely yields sane values instead of a {@code NullPointerException} on first use.
 *
 * <p>NO SECRETS HAVE DEFAULTS THAT WOULD WORK. The keystore password has no default at all: a
 * service that starts with a built-in signing-key password is a service that will eventually be
 * deployed with one.
 *
 * @param jwt access-token issuance and signing-key selection
 * @param refresh refresh-token lifetimes and the rotation race window
 * @param cookie refresh-cookie attributes
 * @param registration self-service sign-up switch
 * @param lockout per-account brute-force policy
 * @param rateLimit per-IP soft throttle
 * @param oauth2 Google OIDC login
 * @param housekeeping dead-row sweep
 * @param storefront storefront origin for links in emails
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    @DefaultValue Jwt jwt,
    @DefaultValue Refresh refresh,
    @DefaultValue Cookie cookie,
    @DefaultValue Registration registration,
    @DefaultValue Lockout lockout,
    @DefaultValue RateLimit rateLimit,
    @DefaultValue Oauth2 oauth2,
    @DefaultValue Housekeeping housekeeping,
    @DefaultValue Storefront storefront) {

  /**
   * Storefront links used in emails (password reset, email verification).
   *
   * @param baseUrl origin of the storefront, no trailing slash
   */
  public record Storefront(@DefaultValue("http://localhost:3000") String baseUrl) {}

  /**
   * Access-token issuance.
   *
   * @param issuer the {@code iss} claim. Every resource server pins this; changing it invalidates
   *     every token in flight and every service's configuration at the same time.
   * @param audiences the {@code aud} claim. A token minted for this platform must name the services
   *     allowed to accept it, otherwise a token issued for one service is replayable against every
   *     other service that trusts the same issuer.
   * @param accessTokenTtl 10 minutes. Short enough that a leaked access token is a bounded
   *     incident, long enough that the SPA is not refreshing constantly.
   * @param keystorePath PKCS#12 keystore holding the RSA signing key(s). Produced by {@code
   *     infra/scripts/generate-keystore.sh} and MOUNTED, never generated at startup - a key
   *     generated in-process is a different key on every replica and after every restart, so every
   *     token would be unverifiable by everyone else.
   * @param keystorePassword no default; supplied from the environment / secret manager
   * @param activeKid selects the SIGNING key among the keystore's entries. Accepts either the
   *     keystore alias (what a human knows) or the RFC 7638 thumbprint (what appears as {@code kid}
   *     in the JWT header). Blank is allowed only when the keystore holds exactly one key.
   */
  public record Jwt(
      @DefaultValue("https://auth.acme-ecommerce.internal") String issuer,
      @DefaultValue("ecommerce-platform") List<String> audiences,
      @DefaultValue("10m") Duration accessTokenTtl,
      @DefaultValue("infra/secrets/jwt-keystore.p12") String keystorePath,
      String keystorePassword,
      @DefaultValue("") String activeKid) {}

  /**
   * Refresh-token lifetimes.
   *
   * @param ttl the SLIDING window: every rotation issues a child valid this long from now
   * @param familyMaxLifetime the ABSOLUTE cap on a login session, fixed when the family root is
   *     created and copied unchanged to every descendant. Without it, an attacker who steals a
   *     refresh token and rotates it politely every 13 days keeps the session alive forever.
   * @param rotationGraceWindow how long after a rotation a replay of the parent is forgiven as a
   *     client race rather than treated as theft. Two browser tabs refreshing at once, or an HTTP
   *     client retrying a request whose response was lost, both produce exactly this pattern. 10s
   *     is comfortably longer than any legitimate retry and far shorter than the window an attacker
   *     would need for a stolen token to be useful.
   */
  public record Refresh(
      @DefaultValue("14d") Duration ttl,
      @DefaultValue("30d") Duration familyMaxLifetime,
      @DefaultValue("10s") Duration rotationGraceWindow) {}

  /**
   * Refresh-cookie attributes.
   *
   * @param name cookie name
   * @param path scoped to the auth endpoints, so the refresh token is not attached to every request
   *     the SPA makes to the rest of the platform
   * @param secure MUST be true anywhere but plain-HTTP local development
   * @param sameSite {@code Lax} rather than {@code Strict}: the Google login flow returns via a
   *     cross-site redirect, and {@code Strict} would withhold the cookie on that navigation. Lax
   *     still blocks the cross-site POST that CSRF needs.
   * @param domain optional; leave blank to scope to the exact host
   */
  public record Cookie(
      @DefaultValue("refresh_token") String name,
      @DefaultValue("/api/auth") String path,
      @DefaultValue("true") boolean secure,
      @DefaultValue("Lax") String sameSite,
      @DefaultValue("") String domain) {}

  /**
   * Self-service registration.
   *
   * @param enabled OFF by default. This is an internal admin console: accounts are provisioned, not
   *     signed up for. Turning it on is a deliberate act, not the result of forgetting to turn it
   *     off.
   */
  public record Registration(@DefaultValue("false") boolean enabled) {}

  /**
   * Per-account brute-force policy. THE real control (see {@code UserCredential}).
   *
   * @param maxFailedAttempts failures before the first lock
   * @param baseDuration first lock length; doubles per additional failure
   * @param maxDuration cap, so an attacker cannot lock a real user out indefinitely
   */
  public record Lockout(
      @DefaultValue("5") int maxFailedAttempts,
      @DefaultValue("1m") Duration baseDuration,
      @DefaultValue("15m") Duration maxDuration) {}

  /**
   * Per-IP soft throttle. In-memory and therefore PER REPLICA - deliberately a nuisance control,
   * not a security boundary. Redis-backed distributed limiting is deferred.
   *
   * @param enabled master switch
   * @param loginCapacity requests per window for {@code /login} and {@code /register}
   * @param refreshCapacity requests per window for {@code /refresh}; higher, because a legitimate
   *     multi-tab SPA refreshes more often than it logs in
   * @param window refill period
   * @param trustForwardedFor read the client IP from {@code X-Forwarded-For}. ONLY safe when this
   *     service is unreachable except through a proxy that overwrites the header - otherwise any
   *     client can spoof it and the throttle becomes decorative.
   * @param maxTrackedClients bound on the IP -> bucket map, so a spoofed-source flood cannot grow
   *     it without limit
   */
  public record RateLimit(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("10") int loginCapacity,
      @DefaultValue("60") int refreshCapacity,
      @DefaultValue("1m") Duration window,
      @DefaultValue("false") boolean trustForwardedFor,
      @DefaultValue("50000") int maxTrackedClients) {}

  /**
   * Google OIDC login.
   *
   * @param clientId from {@code GOOGLE_CLIENT_ID}. Blank disables the whole Google leg - the
   *     service starts and every other endpoint works normally.
   * @param clientSecret from {@code GOOGLE_CLIENT_SECRET}
   * @param allowedRedirectUris ALLOWLIST for the post-login redirect. Anything not on this list is
   *     refused; without it {@code ?redirect_uri=https://evil.example} turns the login endpoint
   *     into an open redirect that launders phishing links through a trusted domain.
   * @param defaultRedirectUri where to send the browser when no target was requested
   * @param stateCookieSecret optional HMAC key for the authorization-request cookie. When blank, a
   *     key is derived from the JWT signing key, which every replica already shares.
   */
  public record Oauth2(
      @DefaultValue("") String clientId,
      @DefaultValue("") String clientSecret,
      @DefaultValue("http://localhost:5173/auth/callback") List<String> allowedRedirectUris,
      @DefaultValue("http://localhost:5173/auth/callback") String defaultRedirectUri,
      @DefaultValue("") String stateCookieSecret) {

    /** Whether enough configuration is present to register a Google client at all. */
    public boolean configured() {
      return clientId != null
          && !clientId.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank();
    }
  }

  /**
   * Dead-row sweep for {@code refresh_tokens}.
   *
   * @param enabled off in tests
   * @param retention how long a revoked/expired row is kept for forensics before deletion
   * @param interval sweep period
   */
  public record Housekeeping(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("30d") Duration retention,
      @DefaultValue("1h") Duration interval) {}
}
