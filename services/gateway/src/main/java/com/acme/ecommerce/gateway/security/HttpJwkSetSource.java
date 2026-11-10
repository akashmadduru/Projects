package com.acme.ecommerce.gateway.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place the gateway actually fetches a JWKS document over the network.
 *
 * <p>This is the <em>bottom</em> of the Nimbus source stack. Everything that makes the gateway
 * outage-tolerant, rate-limited and refresh-ahead cached is layered on top of it by {@link
 * JwksReactiveJwtDecoderFactory}; this class does exactly one thing - one HTTP GET, parsed - so
 * that the caching policy above it is readable in one place.
 *
 * <p>Uses the JDK {@link HttpClient} rather than {@code WebClient}. The contract it implements is
 * synchronous, and a synchronous JDK call is honest about that; going through {@code WebClient} and
 * then {@code .block()}-ing would be the same blocking call wearing a reactive costume, plus a
 * Reactor "blocking call in non-blocking context" landmine for whoever moves it later.
 *
 * <p>EVERY failure mode here throws {@link JWKSetUnavailableException}, never a bare {@link
 * KeySourceException}, and that is NOT a stylistic choice. Nimbus' {@code
 * OutageTolerantJWKSetSource} and {@code RetryingJWKSetSource} catch exactly {@code
 * JWKSetUnavailableException}; a plain {@code KeySourceException} propagates straight through both
 * of them. Get this wrong and the outage-tolerant cache is configured, reads as configured, and
 * does nothing - a fact only discovered the day auth-service goes down and takes every service's
 * token validation with it. There is a test asserting the end-to-end behaviour.
 *
 * <p>A malformed document is reported as an outage too. It is not literally one, but the useful
 * response is identical: keep serving the last known good key set rather than reject every request
 * in the fleet because someone deployed a broken JWKS endpoint.
 *
 * @param <C> Nimbus security-context type
 */
public class HttpJwkSetSource<C extends SecurityContext> implements JWKSetSource<C> {

  /**
   * Hard cap on the response body. A JWKS document holding a handful of RSA public keys is a couple
   * of KB; without a cap, a compromised or confused upstream could stream until the gateway heaps
   * out.
   */
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;

  private static final Logger log = LoggerFactory.getLogger(HttpJwkSetSource.class);

  private final JwkSetUriResolver uriResolver;
  private final Duration readTimeout;
  private final HttpClient httpClient;
  private final AtomicLong fetchCount = new AtomicLong();

  public HttpJwkSetSource(
      JwkSetUriResolver uriResolver, Duration connectTimeout, Duration readTimeout) {
    this.uriResolver = Objects.requireNonNull(uriResolver, "uriResolver");
    this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
            // NEVER follow a redirect for key material. A 302 from a compromised (or merely
            // misconfigured) auth-service to an attacker-controlled host would hand over the
            // ability to mint tokens the whole platform accepts.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * Number of upstream fetches performed since startup.
   *
   * <p>Exposed for the test that asserts a burst of requests carrying an unknown {@code kid}
   * produces ONE fetch and not one per request - which is the difference between a key rotation
   * being a non-event and being a self-inflicted denial of service on auth-service.
   */
  public long fetchCount() {
    return fetchCount.get();
  }

  @Override
  public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime, C context)
      throws KeySourceException {

    URI uri;
    try {
      uri = uriResolver.resolve();
    } catch (RuntimeException unresolvable) {
      throw new JWKSetUnavailableException("Cannot resolve the JWKS endpoint", unresolvable);
    }

    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(readTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();

    try {
      fetchCount.incrementAndGet();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new JWKSetUnavailableException(
            "JWKS endpoint " + uri + " returned HTTP " + response.statusCode());
      }
      String body = response.body();
      if (body == null || body.length() > MAX_RESPONSE_BYTES) {
        throw new JWKSetUnavailableException(
            "JWKS response from " + uri + " was empty or oversized");
      }

      JWKSet jwkSet = JWKSet.parse(body);
      log.info("Fetched JWKS from {} ({} key(s))", uri, jwkSet.getKeys().size());
      return jwkSet;

    } catch (IOException | ParseException failure) {
      throw new JWKSetUnavailableException("Failed to retrieve JWKS from " + uri, failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new JWKSetUnavailableException(
          "Interrupted while retrieving JWKS from " + uri, interrupted);
    }
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
