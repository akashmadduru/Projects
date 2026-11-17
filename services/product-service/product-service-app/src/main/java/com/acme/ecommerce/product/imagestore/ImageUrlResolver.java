package com.acme.ecommerce.product.imagestore;

import com.acme.ecommerce.common.core.error.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Owns the {@code parentUrl} setting and turns stored image values into browser URLs.
 *
 * <p>An image value stored without a scheme (e.g. {@code 0a1b.jpg} or {@code
 * <productId>/<uuid>.jpg}) is a KEY on static-image-server and is served as {@code parentUrl + "/"
 * + key}, each path segment percent-encoded. Anything else (an {@code https://} CDN URL, a
 * site-relative {@code /path}) is passed through unchanged, so existing external images keep
 * working.
 *
 * <p>{@code parentUrl} lives in {@code product.image_settings} (single row, like {@code
 * theme_settings}) so an admin can move images to another host at runtime. It is seeded on startup
 * from {@code image-store.public-url} when the row is missing, and cached for a few seconds so
 * every replica picks up a change without a restart.
 */
@Component
public class ImageUrlResolver {

  private static final Logger LOG = LoggerFactory.getLogger(ImageUrlResolver.class);
  private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
  private static final long CACHE_MILLIS = 15_000;
  private static final int MAX_PARENT_URL = 500;

  /**
   * Folder prefixes the image tooling has written into {@code product_images.url}; the file name
   * after them is the key on static-image-server.
   */
  static final List<String> LEGACY_PREFIXES =
      List.of("output/images/", "product-images/images/", "static/images/");

  /** {@code parentUrl} is null until configured; {@code updatedAt} null for the env default. */
  public record ImageSettings(String parentUrl, Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private final String defaultParentUrl;

  private volatile ImageSettings cached;
  private volatile long cachedAt;

  public ImageUrlResolver(
      JdbcTemplate jdbc, @Value("${image-store.public-url:}") String defaultParentUrl) {
    this.jdbc = jdbc;
    String normalized = null;
    try {
      normalized =
          defaultParentUrl == null || defaultParentUrl.isBlank()
              ? null
              : normalizeParentUrl(defaultParentUrl);
    } catch (ValidationException e) {
      LOG.warn(
          "Ignoring invalid image-store.public-url '{}': {}", defaultParentUrl, e.getMessage());
    }
    this.defaultParentUrl = normalized;
  }

  // ---- URL building ---------------------------------------------------------------------------

  /** Browser URL for a stored image value; null/blank stays null. */
  public String resolve(String stored) {
    return join(parentUrl(), stored);
  }

  /**
   * Inverse of {@link #resolve} for values a client sends back: a URL under the current {@code
   * parentUrl} is stored as its (decoded) key, so it keeps following later {@code parentUrl}
   * changes. Anything else is stored as given.
   */
  public String toStored(String url) {
    if (url == null) {
      return null;
    }
    String value = url.trim();
    String parent = parentUrl();
    if (parent != null && value.startsWith(parent + "/")) {
      String key = UriUtils.decode(value.substring(parent.length() + 1), StandardCharsets.UTF_8);
      return keyOf(key).orElse(value);
    }
    return value;
  }

  /** The static-image-server key behind a stored value, or empty for an external URL. */
  public Optional<String> keyOf(String stored) {
    if (stored == null || stored.isBlank() || isExternal(stored.trim())) {
      return Optional.empty();
    }
    String key = stored.trim();
    for (String prefix : LEGACY_PREFIXES) {
      if (key.startsWith(prefix)) {
        key = key.substring(prefix.length());
        break;
      }
    }
    List<String> segments = new ArrayList<>();
    for (String segment : key.split("/")) {
      if (!segment.isEmpty() && !segment.equals(".") && !segment.equals("..")) {
        segments.add(segment);
      }
    }
    return segments.isEmpty() ? Optional.empty() : Optional.of(String.join("/", segments));
  }

  /** {@code parent + "/" + encoded key}; external values and a missing parent pass through. */
  String join(String parent, String stored) {
    if (stored == null || stored.isBlank()) {
      return null;
    }
    Optional<String> key = keyOf(stored);
    if (key.isEmpty() || parent == null) {
      return stored.trim();
    }
    StringBuilder url = new StringBuilder(parent);
    for (String segment : key.get().split("/")) {
      url.append('/').append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
    }
    return url.toString();
  }

  private static boolean isExternal(String value) {
    return value.startsWith("/") || HAS_SCHEME.matcher(value).find();
  }

  // ---- the parentUrl setting --------------------------------------------------------------------

  public String parentUrl() {
    return settings().parentUrl();
  }

  public ImageSettings settings() {
    ImageSettings current = cached;
    if (current == null || System.currentTimeMillis() - cachedAt > CACHE_MILLIS) {
      current = load();
      cached = current;
      cachedAt = System.currentTimeMillis();
    }
    return current;
  }

  /** Sets {@code parentUrl}; takes effect on this instance at once, on others within seconds. */
  public ImageSettings update(String parentUrl) {
    String clean = normalizeParentUrl(parentUrl);
    jdbc.update(
        "INSERT INTO product.image_settings (id, parent_url, updated_at) VALUES (1, ?, now()) "
            + "ON CONFLICT (id) DO UPDATE SET parent_url = EXCLUDED.parent_url, updated_at = now()",
        clean);
    return refresh();
  }

  /** Back to the startup default ({@code image-store.public-url}). */
  public ImageSettings reset() {
    jdbc.update("DELETE FROM product.image_settings");
    initialize();
    return refresh();
  }

  /** On startup, seed {@code parentUrl} from the static-image-server endpoint if none is set. */
  @EventListener(ApplicationReadyEvent.class)
  public void initialize() {
    if (defaultParentUrl == null) {
      LOG.info("image-store.public-url not set; relative product image keys are served as-is");
      return;
    }
    try {
      int inserted =
          jdbc.update(
              "INSERT INTO product.image_settings (id, parent_url) VALUES (1, ?) "
                  + "ON CONFLICT (id) DO NOTHING",
              defaultParentUrl);
      LOG.info(
          inserted == 1
              ? "Initialized image parentUrl to {}"
              : "Image parentUrl already set; startup default {} not applied",
          defaultParentUrl);
    } catch (RuntimeException e) {
      LOG.warn("Could not initialize image parentUrl: {}", e.getMessage());
    }
    refresh();
  }

  private ImageSettings refresh() {
    cached = load();
    cachedAt = System.currentTimeMillis();
    return cached;
  }

  private ImageSettings load() {
    try {
      List<ImageSettings> rows =
          jdbc.query(
              "SELECT parent_url, updated_at FROM product.image_settings WHERE id = 1",
              (rs, i) -> {
                Timestamp ts = rs.getTimestamp("updated_at");
                return new ImageSettings(
                    rs.getString("parent_url"), ts == null ? null : ts.toInstant());
              });
      return rows.isEmpty() ? new ImageSettings(defaultParentUrl, null) : rows.get(0);
    } catch (RuntimeException e) {
      // Keep serving with the last known value (or the env default) through a DB hiccup.
      ImageSettings last = cached;
      LOG.warn("Could not read image settings: {}", e.getMessage());
      return last != null ? last : new ImageSettings(defaultParentUrl, null);
    }
  }

  /**
   * Validates and canonicalises a parent URL: absolute http(s), a host, no query or fragment,
   * illegal characters percent-encoded (already-encoded input is kept as is), no trailing slash.
   */
  static String normalizeParentUrl(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      throw ValidationException.forField("parentUrl", "is required");
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException notEncoded) {
      try {
        uri = UriComponentsBuilder.fromUriString(value).encode().build().toUri();
      } catch (RuntimeException e) {
        throw ValidationException.forField("parentUrl", "is not a valid URL");
      }
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw ValidationException.forField("parentUrl", "must be an absolute http(s) URL");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw ValidationException.forField("parentUrl", "must include a host");
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
      throw ValidationException.forField(
          "parentUrl", "must not contain a query, fragment or credentials");
    }
    String clean = uri.toASCIIString();
    while (clean.endsWith("/")) {
      clean = clean.substring(0, clean.length() - 1);
    }
    if (clean.length() > MAX_PARENT_URL) {
      throw ValidationException.forField("parentUrl", "must be at most 500 characters");
    }
    return clean;
  }
}
