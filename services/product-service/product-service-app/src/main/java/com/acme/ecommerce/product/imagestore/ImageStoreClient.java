package com.acme.ecommerce.product.imagestore;

import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to the static image server (nginx WebDAV, see {@code deploy/local/image-server}).
 *
 * <p>Writes are {@code PUT}/{@code DELETE} on {@code {baseUrl}/images/{key}} with the shared {@code
 * X-Image-Token}; browsers read the same file at {@code {publicUrl}/{key}}. Disabled (every call
 * throws {@link IllegalStateException}, {@link #isEnabled()} is false) when {@code
 * image-store.url} is blank, so environments without an image server keep working with plain
 * external image URLs.
 */
@Component
public class ImageStoreClient {

  private static final Logger LOG = LoggerFactory.getLogger(ImageStoreClient.class);

  private final RestClient http;
  private final String publicUrl;

  public ImageStoreClient(
      @Value("${image-store.url:}") String baseUrl,
      @Value("${image-store.public-url:}") String publicUrl,
      @Value("${image-store.token:}") String token) {
    this.publicUrl = stripTrailingSlash(publicUrl);
    String base = stripTrailingSlash(baseUrl);
    this.http =
        base.isEmpty()
            ? null
            : RestClient.builder()
                .baseUrl(base + "/images")
                .defaultHeader("X-Image-Token", token)
                .build();
  }

  public boolean isEnabled() {
    return http != null && !publicUrl.isEmpty();
  }

  /** Browser-facing URL for a stored key. */
  public String publicUrlFor(String key) {
    return publicUrl + "/" + key;
  }

  /** The key behind a public URL of ours, or empty for an external URL. */
  public Optional<String> keyFor(String url) {
    if (!isEnabled() || url == null || !url.startsWith(publicUrl + "/")) {
      return Optional.empty();
    }
    String key = URI.create(url).getPath();
    String prefix = URI.create(publicUrl).getPath() + "/";
    return key.startsWith(prefix) && key.length() > prefix.length()
        ? Optional.of(key.substring(prefix.length()))
        : Optional.empty();
  }

  /** Create or overwrite {@code key}. */
  public void put(String key, byte[] bytes, String contentType) {
    requireEnabled();
    http.put()
        .uri("/{key}", key)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.parseMediaType(contentType).toString())
        .body(bytes)
        .retrieve()
        .toBodilessEntity();
  }

  /** Delete {@code key}; a missing file is not an error. */
  public void delete(String key) {
    requireEnabled();
    http.delete()
        .uri("/{key}", key)
        .retrieve()
        .onStatus(status -> status.value() == 404, (req, res) -> {})
        .toBodilessEntity();
  }

  /** Best-effort delete for cleanup paths where the database change must not fail on the file. */
  public void deleteQuietly(String key) {
    try {
      delete(key);
    } catch (RuntimeException e) {
      LOG.warn("Could not delete image {} from the image server: {}", key, e.getMessage());
    }
  }

  private void requireEnabled() {
    if (!isEnabled()) {
      throw new IllegalStateException("Image server is not configured (image-store.url)");
    }
  }

  private static String stripTrailingSlash(String value) {
    String v = value == null ? "" : value.trim();
    return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
  }
}
