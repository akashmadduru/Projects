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
  // ---- allow-lists: GENERATED from themeEngine.ts / themeFonts.ts, keep in sync ----
  private static final Set<String> RADII =
      Set.of("square", "micro", "sharp", "soft", "round", "pill", "mega");
  private static final Set<String> BORDER_WIDTHS =
      Set.of("none", "thin", "standard", "bold", "heavy");
  private static final Set<String> BORDER_COLORS =
      Set.of("subtle", "muted", "default", "strong", "brand", "accent");
  private static final Set<String> BORDER_STYLES = Set.of("solid", "dashed", "dotted", "double");
  private static final Set<String> SHADOW_INTENSITIES =
      Set.of("whisper", "light", "medium", "strong", "dramatic");
  private static final Set<String> SHADOW_TINTS = Set.of("ink", "brand", "accent", "danger", "duo");
  private static final Set<String> CARD_HOVERS =
      Set.of(
          "default", "none", "lift", "float", "glow", "scale", "pop", "tilt", "press", "border",
          "dim");
  private static final Set<String> CARD_FILLS =
      Set.of(
          "default",
          "tinted",
          "accent",
          "muted",
          "gradient",
          "glass",
          "outline",
          "dots",
          "stripes",
          "grid");
  private static final Set<String> CARD_SHAPES = Set.of("default", "leaf", "notch", "top");
  private static final Set<String> CARD_ACCENTS = Set.of("none", "top", "left", "bottom");
  private static final Set<String> BUTTON_SHAPES =
      Set.of("default", "square", "rounded", "squircle", "pill", "leaf", "tab");
  private static final Set<String> BUTTON_FILLS =
      Set.of("default", "solid", "brand", "soft", "outline", "ghost", "glass", "inverse");
  private static final Set<String> BUTTON_TEXTS =
      Set.of("default", "upper", "bold", "wide", "smallcaps");
  private static final Set<String> BUTTON_HOVERS =
      Set.of("default", "none", "lift", "grow", "glow", "darken");
  private static final Set<String> HEADING_WEIGHTS =
      Set.of("default", "medium", "semibold", "bold");
  private static final Set<String> TRACKINGS = Set.of("tight", "default", "wide");
  private static final Set<String> LINE_HEIGHTS = Set.of("compact", "default", "relaxed");
  private static final Set<String> SHADOWS =
      Set.of(
          "none",
          "soft",
          "layered",
          "lifted",
          "dreamy",
          "floating",
          "elevated",
          "paper",
          "glow",
          "neon",
          "ring",
          "inner",
          "crisp",
          "retro",
          "long",
          "stacked",
          "drop",
          "left");

  /** Families verified to serve real 400-700 weights on Google Fonts (see themeFonts.ts). */
  private static final Set<String> FONTS =
      Set.of(
          "Afacad",
          "Albert Sans",
          "Anek Latin",
          "Archivo",
          "Asap",
          "Assistant",
          "Atkinson Hyperlegible Next",
          "Bai Jamjuree",
          "Barlow",
          "Be Vietnam Pro",
          "Cabin",
          "Cairo",
          "Catamaran",
          "Chakra Petch",
          "Chivo",
          "Commissioner",
          "DM Sans",
          "Darker Grotesque",
          "Encode Sans",
          "Encode Sans Expanded",
          "Exo 2",
          "Familjen Grotesk",
          "Figtree",
          "Fira Sans",
          "Funnel Sans",
          "Gantari",
          "Geist",
          "Geologica",
          "Golos Text",
          "Hanken Grotesk",
          "Heebo",
          "Hind",
          "Host Grotesk",
          "Hubot Sans",
          "IBM Plex Sans",
          "Instrument Sans",
          "Inter",
          "Inter Tight",
          "Jost",
          "Kanit",
          "Karla",
          "Kumbh Sans",
          "League Spartan",
          "Lexend",
          "Lexend Deca",
          "Libre Franklin",
          "Mada",
          "Manrope",
          "Merriweather Sans",
          "Mitr",
          "Mona Sans",
          "Montserrat",
          "Montserrat Alternates",
          "Mukta",
          "Mulish",
          "Noto Sans",
          "Noto Sans Display",
          "Nunito Sans",
          "Onest",
          "Open Sans",
          "Outfit",
          "Overpass",
          "Pathway Extreme",
          "Plus Jakarta Sans",
          "Poppins",
          "Prompt",
          "Public Sans",
          "Radio Canada",
          "Radio Canada Big",
          "Raleway",
          "Readex Pro",
          "Red Hat Text",
          "Reddit Sans",
          "Rethink Sans",
          "Roboto",
          "Rubik",
          "Saira",
          "Sarabun",
          "Sen",
          "Signika",
          "Sofia Sans",
          "Sora",
          "Source Sans 3",
          "Spline Sans",
          "Ubuntu Sans",
          "Varta",
          "Wix Madefor Display",
          "Wix Madefor Text",
          "Work Sans",
          "Alegreya",
          "Aleo",
          "Besley",
          "Bodoni Moda",
          "Brygada 1918",
          "Castoro",
          "Cinzel",
          "Cormorant",
          "Cormorant Garamond",
          "Cormorant Infant",
          "Cormorant Upright",
          "Crimson Pro",
          "Domine",
          "EB Garamond",
          "Eczar",
          "Faustina",
          "Frank Ruhl Libre",
          "Fraunces",
          "Gelasio",
          "Hahmlet",
          "IBM Plex Serif",
          "Inknut Antiqua",
          "Libre Baskerville",
          "Literata",
          "Lora",
          "Merriweather",
          "Newsreader",
          "Noto Serif",
          "Noto Serif Display",
          "Petrona",
          "Piazzolla",
          "Playfair",
          "Playfair Display",
          "Roboto Serif",
          "Source Serif 4",
          "Spectral",
          "Texturina",
          "Vollkorn",
          "Bricolage Grotesque",
          "Epilogue",
          "Exo",
          "Funnel Display",
          "Gabarito",
          "Josefin Sans",
          "Jura",
          "Kodchasan",
          "Lexend Zetta",
          "Orbitron",
          "Red Hat Display",
          "Schibsted Grotesk",
          "Space Grotesk",
          "Syne",
          "Tektur",
          "Unbounded",
          "Urbanist",
          "Baloo 2",
          "Comfortaa",
          "Fredoka",
          "Grandstander",
          "Mali",
          "Nunito",
          "Quicksand",
          "Bitter",
          "Josefin Slab",
          "Kreon",
          "Podkova",
          "Roboto Slab",
          "Rokkitt",
          "Zilla Slab",
          "Antonio",
          "Archivo Narrow",
          "Asap Condensed",
          "Barlow Condensed",
          "Barlow Semi Condensed",
          "Big Shoulders Display",
          "Encode Sans Condensed",
          "Fira Sans Condensed",
          "Khand",
          "Oswald",
          "Rajdhani",
          "Reddit Sans Condensed",
          "Roboto Condensed",
          "Saira Condensed",
          "Smooch Sans",
          "Sofia Sans Condensed",
          "Teko",
          "Azeret Mono",
          "Chivo Mono",
          "Fira Code",
          "Geist Mono",
          "IBM Plex Mono",
          "JetBrains Mono",
          "Martian Mono",
          "Noto Sans Mono",
          "Recursive",
          "Red Hat Mono",
          "Roboto Mono",
          "Sometype Mono",
          "Source Code Pro",
          "Spline Sans Mono",
          "Caveat",
          "Dancing Script",
          "Edu SA Beginner",
          "system-ui");

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

  /** Corner radius, outline thickness and outline colour. */
  public record Border(String radius, String width, String color, String style) {}

  public record Shadow(String style, String intensity, String tint) {}

  /** Card hover behaviour and fill. */
  public record Card(String hover, String fill, String shape, String accent) {}

  /** Call-to-action button shape, fill and label style. */
  public record Button(String shape, String fill, String text, String hover) {}

  /** Design language id; the effects themselves are rendered client-side. */
  public record Style(String id) {}

  /** Global comfort options: text size and motion. */
  public record Options(String textScale, String motion) {}

  public record Fonts(
      String display, String body, String headingWeight, String tracking, String lineHeight) {}

  public record ThemeConfig(
      String preset,
      Brand brand,
      Palette light,
      Palette dark,
      Border border,
      Shadow shadow,
      Card card,
      Button button,
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
    // Fields added after the first release are optional: null falls back to the built-in value.
    Border bd = c.border() == null ? new Border(null, null, null, null) : c.border();
    Border border =
        new Border(
            oneOf("border.radius", bd.radius(), RADII, "soft"),
            oneOf("border.width", bd.width(), BORDER_WIDTHS, "standard"),
            oneOf("border.color", bd.color(), BORDER_COLORS, "default"),
            oneOf("border.style", bd.style(), BORDER_STYLES, "solid"));
    Shadow sh = c.shadow() == null ? new Shadow(null, null, null) : c.shadow();
    Shadow shadow =
        new Shadow(
            oneOf("shadow.style", sh.style(), SHADOWS, "retro"),
            oneOf("shadow.intensity", sh.intensity(), SHADOW_INTENSITIES, "medium"),
            oneOf("shadow.tint", sh.tint(), SHADOW_TINTS, "ink"));
    Card cd = c.card() == null ? new Card(null, null, null, null) : c.card();
    Card card =
        new Card(
            oneOf("card.hover", cd.hover(), CARD_HOVERS, "default"),
            oneOf("card.fill", cd.fill(), CARD_FILLS, "default"),
            oneOf("card.shape", cd.shape(), CARD_SHAPES, "default"),
            oneOf("card.accent", cd.accent(), CARD_ACCENTS, "none"));
    Button bt = c.button() == null ? new Button(null, null, null, null) : c.button();
    Button button =
        new Button(
            oneOf("button.shape", bt.shape(), BUTTON_SHAPES, "default"),
            oneOf("button.fill", bt.fill(), BUTTON_FILLS, "default"),
            oneOf("button.text", bt.text(), BUTTON_TEXTS, "default"),
            oneOf("button.hover", bt.hover(), BUTTON_HOVERS, "default"));
    Style designStyle =
        new Style(oneOf("style.id", c.style() == null ? null : c.style().id(), STYLES, "default"));
    Options op = c.options() == null ? new Options("default", "full") : c.options();
    Options options =
        new Options(
            oneOf("options.textScale", op.textScale(), TEXT_SCALES, "default"),
            oneOf("options.motion", op.motion(), MOTION, "full"));
    Fonts f = c.fonts() == null ? new Fonts("Quicksand", "Poppins", null, null, null) : c.fonts();
    Fonts fonts =
        new Fonts(
            font("fonts.display", f.display()),
            font("fonts.body", f.body()),
            oneOf("fonts.headingWeight", f.headingWeight(), HEADING_WEIGHTS, "default"),
            oneOf("fonts.tracking", f.tracking(), TRACKINGS, "default"),
            oneOf("fonts.lineHeight", f.lineHeight(), LINE_HEIGHTS, "default"));
    return new ThemeConfig(
        preset.isEmpty() ? "custom" : preset,
        brand,
        palette("light", c.light()),
        palette("dark", c.dark()),
        border,
        shadow,
        card,
        button,
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
      throw ValidationException.forField(field, "must be one of the supported font families");
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
