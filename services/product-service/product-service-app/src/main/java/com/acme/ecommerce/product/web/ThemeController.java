package com.acme.ecommerce.product.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Theme and branding shared by the admin console and the storefront: public {@code GET
 * /api/products/theme}, admin {@code PUT|DELETE /api/admin/theme}.
 *
 * <p>The config is served to anonymous visitors and applied as CSS, so it is validated here against
 * a closed schema (hex colours, allow-listed fonts, bounded text) and the free-form {@code
 * customCss} is rejected if it could load remote resources or break out of a {@code <style>} tag.
 */
@RestController
@RequiredArgsConstructor
public class ThemeController {

  private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
  private static final Pattern SAFE_URL = Pattern.compile("^(https://|/)[^\\s\"'<>()\\\\]{1,490}$");
  private static final Set<String> RADII = Set.of("sharp", "soft", "round");
  private static final Set<String> BORDER_WIDTHS = Set.of("thin", "standard", "bold");
  private static final Set<String> TEXT_SCALES = Set.of("small", "default", "large");
  private static final Set<String> MOTION = Set.of("full", "reduced", "none");
  private static final Set<String> STYLES =
      Set.of(
          "default",
          "minimalism",
          "flat-design",
          "flat-2",
          "material",
          "fluent",
          "hig",
          "dark-mode",
          "skeuomorphism",
          "neumorphism",
          "glassmorphism",
          "claymorphism",
          "liquid-glass",
          "aurora-ui",
          "bento-grid",
          "editorial-swiss",
          "brutalism",
          "neubrutalism",
          "retro-vintage",
          "y2k",
          "cyberpunk",
          "bauhaus",
          "maximalism",
          "spatial-ui",
          "conversational-ai");
  private static final Set<String> SHADOWS = Set.of("none", "soft", "crisp", "retro", "lifted");
  private static final Set<String> FONTS =
      Set.of(
          "Quicksand",
          "Poppins",
          "Inter",
          "DM Sans",
          "Manrope",
          "Space Grotesk",
          "Playfair Display",
          "Lora",
          "Nunito",
          "Montserrat",
          "Raleway",
          "Work Sans",
          "Rubik",
          "Outfit",
          "Plus Jakarta Sans",
          "Sora",
          "Josefin Sans",
          "Cormorant Garamond",
          "Fraunces",
          "Archivo",
          "Karla",
          "Source Sans 3",
          "IBM Plex Sans",
          "Oswald",
          "system-ui");
  private static final List<String> FORBIDDEN_CSS =
      List.of(
          "@import", "url(", "expression", "javascript:", "behavior", "binding", "<", ">", "\\");
  private static final int MAX_CSS = 4000;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public record Brand(String name, String tagline, String logoUrl) {}

  public record Palette(
      String primary,
      String accent,
      String background,
      String surface,
      String text,
      String danger) {}

  /** Corner radius and outline thickness. */
  public record Border(String radius, String width) {}

  public record Shadow(String style) {}

  /** Design language id; the effects themselves are rendered client-side. */
  public record Style(String id) {}

  /** Global comfort options: text size and motion. */
  public record Options(String textScale, String motion) {}

  public record Fonts(String display, String body) {}

  public record ThemeConfig(
      String preset,
      Brand brand,
      Palette light,
      Palette dark,
      Border border,
      Shadow shadow,
      Style style,
      Options options,
      Fonts fonts,
      String customCss) {}

  /** {@code config} is null until an admin saves one. */
  public record ThemePayload(ThemeConfig config, Instant updatedAt) {}

  @GetMapping("/api/products/theme")
  public ResponseEntity<ThemePayload> get() {
    return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePublic()).body(load());
  }

  @PutMapping("/api/admin/theme")
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public ThemePayload put(@RequestBody ThemeConfig body) {
    ThemeConfig clean = validate(body);
    String json;
    try {
      json = mapper.writeValueAsString(clean);
    } catch (JsonProcessingException e) {
      throw ValidationException.forField("config", "could not be serialised");
    }
    jdbc.update(
        "INSERT INTO product.theme_settings (id, config, updated_at) VALUES (1, ?, now()) "
            + "ON CONFLICT (id) DO UPDATE SET config = EXCLUDED.config, updated_at = now()",
        json);
    return load();
  }

  /** Back to the built-in theme. */
  @DeleteMapping("/api/admin/theme")
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public ThemePayload reset() {
    jdbc.update("DELETE FROM product.theme_settings");
    return load();
  }

  private ThemePayload load() {
    return jdbc.query(
        "SELECT config, updated_at FROM product.theme_settings WHERE id = 1",
        rs -> {
          if (!rs.next()) {
            return new ThemePayload(null, null);
          }
          try {
            Timestamp ts = rs.getTimestamp("updated_at");
            return new ThemePayload(
                mapper.readValue(rs.getString("config"), ThemeConfig.class),
                ts == null ? null : ts.toInstant());
          } catch (JsonProcessingException e) {
            return new ThemePayload(null, null); // corrupt row: fall back to the built-in theme
          }
        });
  }

  // ---- validation -----------------------------------------------------------------------------

  private static ThemeConfig validate(ThemeConfig c) {
    if (c == null) {
      throw ValidationException.forField("config", "is required");
    }
    String preset = text("preset", c.preset(), 40, false);
    Brand b = c.brand() == null ? new Brand("", "", "") : c.brand();
    Brand brand =
        new Brand(
            text("brand.name", b.name(), 40, true),
            text("brand.tagline", b.tagline(), 120, false),
            url("brand.logoUrl", b.logoUrl()));
    Border bd = c.border() == null ? new Border("soft", "standard") : c.border();
    Border border =
        new Border(
            oneOf("border.radius", bd.radius(), RADII, "soft"),
            oneOf("border.width", bd.width(), BORDER_WIDTHS, "standard"));
    Shadow shadow =
        new Shadow(
            oneOf(
                "shadow.style", c.shadow() == null ? null : c.shadow().style(), SHADOWS, "retro"));
    Style designStyle =
        new Style(oneOf("style.id", c.style() == null ? null : c.style().id(), STYLES, "default"));
    Options op = c.options() == null ? new Options("default", "full") : c.options();
    Options options =
        new Options(
            oneOf("options.textScale", op.textScale(), TEXT_SCALES, "default"),
            oneOf("options.motion", op.motion(), MOTION, "full"));
    Fonts f = c.fonts() == null ? new Fonts("Quicksand", "Poppins") : c.fonts();
    Fonts fonts = new Fonts(font("fonts.display", f.display()), font("fonts.body", f.body()));
    return new ThemeConfig(
        preset.isEmpty() ? "custom" : preset,
        brand,
        palette("light", c.light()),
        palette("dark", c.dark()),
        border,
        shadow,
        designStyle,
        options,
        fonts,
        css(c.customCss()));
  }

  private static Palette palette(String name, Palette p) {
    if (p == null) {
      throw ValidationException.forField(name, "palette is required");
    }
    return new Palette(
        hex(name + ".primary", p.primary()),
        hex(name + ".accent", p.accent()),
        hex(name + ".background", p.background()),
        hex(name + ".surface", p.surface()),
        hex(name + ".text", p.text()),
        hex(name + ".danger", p.danger()));
  }

  private static String hex(String field, String v) {
    if (v == null || !HEX.matcher(v).matches()) {
      throw ValidationException.forField(field, "must be a #RRGGBB colour");
    }
    return v.toLowerCase(Locale.ROOT);
  }

  private static String text(String field, String v, int max, boolean required) {
    String t = v == null ? "" : v.trim();
    if (required && t.isEmpty()) {
      throw ValidationException.forField(field, "is required");
    }
    if (t.length() > max || t.chars().anyMatch(ch -> ch < 0x20 || ch == '<' || ch == '>')) {
      throw ValidationException.forField(field, "is too long or contains invalid characters");
    }
    return t;
  }

  private static String url(String field, String v) {
    String t = v == null ? "" : v.trim();
    if (!t.isEmpty() && !SAFE_URL.matcher(t).matches()) {
      throw ValidationException.forField(field, "must be an https:// URL or a /path");
    }
    return t;
  }

  /** Null/blank falls back to {@code fallback}; anything else must be in {@code allowed}. */
  private static String oneOf(String field, String v, Set<String> allowed, String fallback) {
    if (v == null || v.isBlank()) {
      return fallback;
    }
    if (!allowed.contains(v)) {
      throw ValidationException.forField(field, "must be one of " + allowed);
    }
    return v;
  }

  private static String font(String field, String v) {
    if (v == null || !FONTS.contains(v)) {
      throw ValidationException.forField(field, "must be one of " + FONTS);
    }
    return v;
  }

  private static String css(String v) {
    String t = v == null ? "" : v.trim();
    if (t.length() > MAX_CSS) {
      throw ValidationException.forField("customCss", "must be at most " + MAX_CSS + " characters");
    }
    String lower = t.toLowerCase(Locale.ROOT);
    for (String bad : FORBIDDEN_CSS) {
      if (lower.contains(bad)) {
        throw ValidationException.forField("customCss", "must not contain '" + bad + "'");
      }
    }
    return t;
  }
}
