package com.acme.ecommerce.product.search;

import com.acme.ecommerce.common.core.time.TimeProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic extraction of obvious constraints from a natural-language query: a price ceiling or
 * floor, a minimum star rating and a known brand. No model, no network - so product-service needs
 * no chat provider and the result is exactly reproducible.
 *
 * <p>RULES OF ENGAGEMENT. Extraction never rewrites the query text (the embedding still sees "under
 * 3000"), runs only for filters the caller did not pass explicitly, and is reported back to the
 * client as {@code interpretedFilters} so the UI can offer to remove each one. Anything ambiguous
 * is left alone: a wrong extracted filter hides products, which is worse than missing one.
 */
@Component
public class QueryUnderstanding {

  /** How long the known-brand list is reused before it is read again. */
  private static final Duration BRANDS_TTL = Duration.ofMinutes(5);

  // Rupee sign and euro sign are written as unicode escapes (u20B9, u20AC); the rest is plain
  // regex.
  private static final String CURRENCY = "(?:\u20B9|rs\\.?|inr|\\$|usd|\u20AC|eur)?\\s*";
  private static final String NUMBER =
      "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*(k\\b)?";

  private static final Pattern RATING =
      Pattern.compile(
          "(?i)\\b(\\d(?:\\.\\d)?)\\s*\\+?\\s*stars?\\b"
              + "(?:\\s*(?:and|&|or)?\\s*(?:above|up|upwards|higher|more|plus|\\+))?");
  private static final Pattern RATED =
      Pattern.compile(
          "(?i)\\brated\\s+(\\d(?:\\.\\d)?)\\s*(?:\\+|(?:and|or)\\s+(?:above|up|more|higher))?");
  private static final Pattern MAX_PRICE =
      Pattern.compile(
          "(?i)(?:\\b(?:under|below|less than|upto|up to|within|cheaper than|not more than|max(?:imum)?)\\s*|<\\s*)"
              + CURRENCY
              + NUMBER);
  private static final Pattern MIN_PRICE =
      Pattern.compile(
          "(?i)(?:\\b(?:above|over|more than|at least|min(?:imum)?|starting (?:at|from))\\s*|>\\s*)"
              + CURRENCY
              + NUMBER);
  private static final Pattern BRAND_CUE = Pattern.compile("(?i)\\b(?:from|by|brand)\\s+(.+)$");

  /** Lists the distinct brands of active products. */
  @FunctionalInterface
  public interface BrandSource extends Supplier<Collection<String>> {}

  private final BrandSource brandSource;
  private final TimeProvider time;

  private volatile List<String> brands = List.of();
  private volatile long brandsLoadedAtMillis = Long.MIN_VALUE;

  public QueryUnderstanding(BrandSource brandSource, TimeProvider time) {
    this.brandSource = brandSource;
    this.time = time;
  }

  /**
   * Interprets the query, keeping only what the caller did not already specify.
   *
   * @param explicit the caller's own filters; a non-null / non-empty component suppresses the
   *     matching extraction
   */
  public InterpretedFilters interpret(String query, SearchFilters explicit) {
    Parsed parsed = parse(query, knownBrands());
    BigDecimal minPrice = explicit.minPrice() == null ? parsed.minPrice() : null;
    BigDecimal maxPrice = explicit.maxPrice() == null ? parsed.maxPrice() : null;
    if (minPrice != null
        && explicit.maxPrice() != null
        && minPrice.compareTo(explicit.maxPrice()) > 0) {
      minPrice = null;
    }
    if (maxPrice != null
        && explicit.minPrice() != null
        && maxPrice.compareTo(explicit.minPrice()) < 0) {
      maxPrice = null;
    }
    BigDecimal minRating = explicit.minRating() == null ? parsed.minRating() : null;
    String brand = explicit.brands() == null || explicit.brands().isEmpty() ? parsed.brand() : null;
    return new InterpretedFilters(minPrice, maxPrice, minRating, brand);
  }

  /** Pure extraction. Package-visible for tests. */
  static Parsed parse(String query, Collection<String> knownBrands) {
    String text = query == null ? "" : query;

    BigDecimal minRating = null;
    Matcher rating = RATING.matcher(text);
    if (rating.find()) {
      minRating = ratingOf(rating.group(1));
      text = mask(text, rating);
    } else {
      Matcher rated = RATED.matcher(text);
      if (rated.find()) {
        minRating = ratingOf(rated.group(1));
        text = mask(text, rated);
      }
    }

    BigDecimal maxPrice = null;
    Matcher max = MAX_PRICE.matcher(text);
    if (max.find()) {
      maxPrice = priceOf(max.group(1), max.group(2));
      text = mask(text, max);
    }
    BigDecimal minPrice = null;
    Matcher min = MIN_PRICE.matcher(text);
    if (min.find()) {
      minPrice = priceOf(min.group(1), min.group(2));
    }
    if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
      minPrice = null;
      maxPrice = null;
    }

    return new Parsed(
        minPrice, maxPrice, minRating, brandOf(query == null ? "" : query, knownBrands));
  }

  /** Result of {@link #parse}. */
  record Parsed(BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating, String brand) {}

  private static String mask(String text, Matcher matcher) {
    return text.substring(0, matcher.start())
        + " ".repeat(matcher.end() - matcher.start())
        + text.substring(matcher.end());
  }

  private static BigDecimal ratingOf(String raw) {
    BigDecimal value = new BigDecimal(raw);
    boolean inRange =
        value.compareTo(BigDecimal.ONE) >= 0 && value.compareTo(BigDecimal.valueOf(5)) <= 0;
    return inRange ? value : null;
  }

  private static BigDecimal priceOf(String number, String thousands) {
    try {
      BigDecimal value = new BigDecimal(number.replace(",", ""));
      if (thousands != null) {
        value = value.multiply(BigDecimal.valueOf(1000));
      }
      return value.signum() > 0 ? value : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The longest known brand that directly follows a "from" / "by" / "brand" cue. */
  private static String brandOf(String query, Collection<String> knownBrands) {
    Matcher cue = BRAND_CUE.matcher(query);
    if (!cue.find()) {
      return null;
    }
    String rest = cue.group(1).strip().toLowerCase(Locale.ROOT);
    return knownBrands.stream()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .filter(brand -> startsWithWord(rest, brand.toLowerCase(Locale.ROOT)))
        .findFirst()
        .orElse(null);
  }

  private static boolean startsWithWord(String text, String brand) {
    if (brand.isBlank() || !text.startsWith(brand)) {
      return false;
    }
    return text.length() == brand.length()
        || !Character.isLetterOrDigit(text.charAt(brand.length()));
  }

  private List<String> knownBrands() {
    long now = time.epochMilli();
    if (brandsLoadedAtMillis == Long.MIN_VALUE
        || now - brandsLoadedAtMillis > BRANDS_TTL.toMillis()) {
      brands = List.copyOf(brandSource.get());
      brandsLoadedAtMillis = now;
    }
    return brands;
  }
}
