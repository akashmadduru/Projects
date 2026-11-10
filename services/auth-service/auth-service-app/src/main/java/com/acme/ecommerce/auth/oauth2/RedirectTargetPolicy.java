package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.config.AuthProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides where the browser may be sent after a federated login.
 *
 * <p>THIS IS THE OPEN-REDIRECT DEFENCE. Without it, {@code
 * /oauth2/authorization/google?redirect_uri=https://evil.example} produces a phishing link that
 * begins on a domain the user trusts, passes through a real Google consent screen, and lands on the
 * attacker's page - with the platform's own domain in the browser history as the referrer. Open
 * redirects on authentication endpoints are also routinely chained into token-leak attacks against
 * flows that DO put credentials in the URL.
 *
 * <p>MATCHING IS EXACT, against a configured list of full URIs, after normalisation. Not a prefix
 * match ({@code https://app.example.com.evil.test} starts with the allowed prefix), not a host
 * suffix match ({@code evil-app.example.com} ends with the allowed suffix), not a regex. Exact
 * matching is the only rule with no known bypass, and the cost is that adding a legitimate new
 * front end is a configuration change - which is the correct amount of friction.
 *
 * <p>Anything unrecognised falls back to the configured default rather than being rejected
 * outright: a user whose bookmark carries a stale {@code redirect_uri} still gets logged in, they
 * simply land on the default page. The attacker gains nothing either way.
 */
@Component
public class RedirectTargetPolicy {

  private static final Logger log = LoggerFactory.getLogger(RedirectTargetPolicy.class);

  private final List<String> allowed;
  private final String defaultTarget;

  public RedirectTargetPolicy(AuthProperties properties) {
    AuthProperties.Oauth2 config = properties.getOauth2();
    this.allowed =
        config.getAllowedRedirectUris().stream().map(RedirectTargetPolicy::normalise).toList();
    this.defaultTarget = normalise(config.getDefaultRedirectUri());

    if (!allowed.contains(defaultTarget)) {
      // Otherwise the fallback path would itself fail validation on the way back
      // out, and every login with no explicit target would break.
      throw new IllegalStateException(
          "auth.oauth2.default-redirect-uri ("
              + defaultTarget
              + ") must also appear in auth.oauth2.allowed-redirect-uris "
              + allowed);
    }
  }

  /**
   * Returns {@code requested} if it is allowlisted, otherwise the configured default.
   *
   * @param requested a client-supplied target; may be null or hostile
   */
  public String resolve(String requested) {
    if (requested == null || requested.isBlank()) {
      return defaultTarget;
    }
    String candidate = normalise(requested);
    if (allowed.contains(candidate)) {
      return candidate;
    }
    log.warn("Rejected non-allowlisted OAuth2 redirect target; falling back to the default");
    return defaultTarget;
  }

  /**
   * The distinct ORIGINS (scheme://host[:port]) of the allowlisted targets.
   *
   * <p>Used as the CORS allowed-origin list, so the set of front ends trusted to receive a login
   * and the set trusted to call the API cross-origin are the same set by construction. Maintaining
   * them as two independent config values is how one of them ends up stale.
   */
  public List<String> allowedOrigins() {
    return allowed.stream().map(RedirectTargetPolicy::originOf).distinct().toList();
  }

  private static String originOf(String normalisedUri) {
    try {
      URI uri = new URI(normalisedUri);
      String origin = uri.getScheme() + "://" + uri.getHost();
      return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
    } catch (URISyntaxException e) {
      return normalisedUri;
    }
  }

  /**
   * Canonical form for comparison: lower-cased scheme and host, default port removed, no fragment.
   *
   * <p>Comparing raw strings would let {@code HTTPS://App.Example.com:443/cb} slip past a list
   * containing {@code https://app.example.com/cb} - and, more importantly, would let an attacker
   * find some other encoding that does the reverse.
   */
  private static String normalise(String value) {
    try {
      URI uri = new URI(value.trim());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      boolean defaultPort =
          port == -1
              || ("http".equals(scheme) && port == 80)
              || ("https".equals(scheme) && port == 443);
      String path = uri.getPath() == null ? "" : uri.getPath();
      String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
      return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path + query;
    } catch (URISyntaxException e) {
      // Unparseable input can never equal a parsed, normalised allowlist entry,
      // so returning it verbatim is safe and keeps the failure visible in logs.
      return value;
    }
  }
}
