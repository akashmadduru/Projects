package com.acme.ecommerce.product.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryUnderstandingTest {

  private static final List<String> BRANDS = List.of("Acme", "Acme Pro", "Globex");
  private static final SearchFilters NONE = new SearchFilters(null, null, null, null, List.of());

  private final QueryUnderstanding understanding =
      new QueryUnderstanding(
          () -> BRANDS, new FixedTimeProvider(Instant.parse("2026-01-01T00:00:00Z")));

  @Test
  @DisplayName("'under N' becomes a price ceiling, with commas, currency symbols and k suffix")
  void maxPrice() {
    assertThat(QueryUnderstanding.parse("laptop under 60000", BRANDS).maxPrice())
        .isEqualByComparingTo("60000");
    assertThat(QueryUnderstanding.parse("laptop below ₹ 60,000", BRANDS).maxPrice())
        .isEqualByComparingTo("60000");
    assertThat(QueryUnderstanding.parse("phone less than $1,299.50", BRANDS).maxPrice())
        .isEqualByComparingTo("1299.50");
    assertThat(QueryUnderstanding.parse("laptop under 60k", BRANDS).maxPrice())
        .isEqualByComparingTo("60000");
  }

  @Test
  @DisplayName("'above N' becomes a price floor; both bounds together are kept")
  void minPrice() {
    QueryUnderstanding.Parsed parsed =
        QueryUnderstanding.parse("watch above 500 under 900", BRANDS);
    assertThat(parsed.minPrice()).isEqualByComparingTo("500");
    assertThat(parsed.maxPrice()).isEqualByComparingTo("900");
  }

  @Test
  @DisplayName("contradictory bounds are dropped rather than returning nothing")
  void contradictoryBounds() {
    QueryUnderstanding.Parsed parsed =
        QueryUnderstanding.parse("watch above 900 under 500", BRANDS);
    assertThat(parsed.minPrice()).isNull();
    assertThat(parsed.maxPrice()).isNull();
  }

  @Test
  @DisplayName("'4 stars and above' is a rating, and the 4 is not mistaken for a price")
  void rating() {
    QueryUnderstanding.Parsed parsed =
        QueryUnderstanding.parse("headphones 4 stars and above", BRANDS);
    assertThat(parsed.minRating()).isEqualByComparingTo("4");
    assertThat(parsed.minPrice()).isNull();
    assertThat(parsed.maxPrice()).isNull();
    assertThat(QueryUnderstanding.parse("4.5 star phone", BRANDS).minRating())
        .isEqualByComparingTo("4.5");
    assertThat(QueryUnderstanding.parse("rated 4+ shoes", BRANDS).minRating())
        .isEqualByComparingTo("4");
  }

  @Test
  @DisplayName("an out-of-range rating is ignored")
  void ratingOutOfRange() {
    assertThat(QueryUnderstanding.parse("9 stars", BRANDS).minRating()).isNull();
  }

  @Test
  @DisplayName("'from <brand>' needs a KNOWN brand; the longest match wins")
  void brand() {
    assertThat(QueryUnderstanding.parse("kettle from Globex", BRANDS).brand()).isEqualTo("Globex");
    assertThat(QueryUnderstanding.parse("kettle by acme pro", BRANDS).brand())
        .isEqualTo("Acme Pro");
    assertThat(QueryUnderstanding.parse("kettle from Initech", BRANDS).brand()).isNull();
    assertThat(QueryUnderstanding.parse("globex kettle", BRANDS).brand()).isNull();
  }

  @Test
  @DisplayName("nothing to extract from a plain query")
  void plainQuery() {
    QueryUnderstanding.Parsed parsed = QueryUnderstanding.parse("wireless headphones", BRANDS);
    assertThat(parsed.minPrice()).isNull();
    assertThat(parsed.maxPrice()).isNull();
    assertThat(parsed.minRating()).isNull();
    assertThat(parsed.brand()).isNull();
  }

  @Test
  @DisplayName("explicit parameters suppress the matching extraction; others still apply")
  void explicitWins() {
    SearchFilters explicit =
        new SearchFilters(UUID.randomUUID(), null, new BigDecimal("100"), null, List.of());
    InterpretedFilters out =
        understanding.interpret("kettle under 3000 4 stars and above from Acme", explicit);
    assertThat(out.maxPrice()).isNull();
    assertThat(out.minRating()).isEqualByComparingTo("4");
    assertThat(out.brand()).isEqualTo("Acme");
  }

  @Test
  @DisplayName("an extracted bound that contradicts an explicit one is discarded")
  void extractedContradictingExplicit() {
    SearchFilters explicit = new SearchFilters(null, new BigDecimal("5000"), null, null, List.of());
    assertThat(understanding.interpret("kettle under 3000", explicit).maxPrice()).isNull();
  }

  @Test
  @DisplayName("an empty interpretation is reported as empty")
  void emptyInterpretation() {
    assertThat(understanding.interpret("kettle", NONE).isEmpty()).isTrue();
  }
}
