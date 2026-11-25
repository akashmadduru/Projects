package com.acme.ecommerce.product.imagestore;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Writes files on static-image-server (nginx WebDAV, see {@code deploy/local/static-image-server}):
 * {@code PUT}/{@code DELETE} on {@code {image-store.url}/{key}} with the shared {@code
 * X-Image-Token}. Browsers read the same file at {@code parentUrl/{key}} (see {@link
 * ImageUrlResolver}).
 *
 * <p>Both calls are idempotent, so a connection failure is retried once. Disabled ({@link
 * #isEnabled()} false, writes throw) when {@code image-store.url} is blank.
 */
@Component
public class ImageStoreClient {

  private static final Logger LOG = LoggerFactory.getLogger(ImageStoreClient.class);

  private final RestClient http;

  public ImageStoreClient(
      @Value("${image-store.url:}") String baseUrl, @Value("${image-store.token:}") String token) {
    String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    requests.setReadTimeout(Duration.ofSeconds(20));
    this.http =
        base.isEmpty()
            ? null
            : RestClient.builder()
                .baseUrl(base)
                .requestFactory(requests)
                .defaultHeader("X-Image-Token", token)
                .build();
  }

  public boolean isEnabled() {
    return http != null;
  }

  /** Create or overwrite {@code key}. */
  public void put(String key, byte[] bytes, String contentType) {
    requireEnabled();
    withRetry(
        () ->
            http.put()
                .uri(uri -> uri.pathSegment(key.split("/")).build())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.parseMediaType(contentType).toString())
                .body(bytes)
                .retrieve()
                .toBodilessEntity());
  }

  /** Delete {@code key}; a missing file is not an error. */
  public void delete(String key) {
    requireEnabled();
    withRetry(
        () ->
            http.delete()
                .uri(uri -> uri.pathSegment(key.split("/")).build())
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {})
                .toBodilessEntity());
  }

  /** Best-effort delete for cleanup paths where the database change must not fail on the file. */
  public void deleteQuietly(String key) {
    try {
      delete(key);
    } catch (RuntimeException e) {
      LOG.warn("Could not delete image {} from static-image-server: {}", key, e.getMessage());
    }
  }

  private static void withRetry(Runnable call) {
    try {
      call.run();
    } catch (ResourceAccessException e) {
      call.run();
    }
  }

  private void requireEnabled() {
    if (!isEnabled()) {
      throw new IllegalStateException("static-image-server is not configured (image-store.url)");
    }
  }
}
