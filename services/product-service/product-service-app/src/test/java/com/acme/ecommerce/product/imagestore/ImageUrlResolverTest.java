package com.acme.ecommerce.product.imagestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ImageUrlResolverTest {

  private static final String PARENT = "http://localhost:8095/product-images";

  private JdbcTemplate jdbc;
  private ImageUrlResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    // No image_settings row: the startup default (image-store.public-url) applies.
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    resolver = new ImageUrlResolver(jdbc, PARENT + "/");
  }

  @Test
  void resolvesKeysAgainstParentUrl() {
    assertThat(resolver.resolve("0a1b.jpg")).isEqualTo(PARENT + "/0a1b.jpg");
    assertThat(resolver.resolve("p1/x.jpg")).isEqualTo(PARENT + "/p1/x.jpg");
  }

  @Test
  void percentEncodesEachKeySegment() {
    assertThat(resolver.resolve("my folder/a b#1?.jpg"))
        .isEqualTo(PARENT + "/my%20folder/a%20b%231%3F.jpg");
  }

  @Test
  void stripsLegacyToolingPrefixesAndDotSegments() {
    assertThat(resolver.resolve("output/images/0a1b.jpg")).isEqualTo(PARENT + "/0a1b.jpg");
    assertThat(resolver.resolve("product-images/images/0a1b.jpg")).isEqualTo(PARENT + "/0a1b.jpg");
    assertThat(resolver.resolve("../../etc/passwd")).isEqualTo(PARENT + "/etc/passwd");
  }

  @Test
  void passesExternalAndSiteRelativeUrlsThrough() {
    String cdn = "https://images.unsplash.com/photo-1?w=400";
    assertThat(resolver.resolve(cdn)).isEqualTo(cdn);
    assertThat(resolver.resolve("/assets/logo.png")).isEqualTo("/assets/logo.png");
    assertThat(resolver.resolve(null)).isNull();
    assertThat(resolver.resolve("  ")).isNull();
  }

  @Test
  void urlsUnderParentAreStoredAsDecodedKeys() {
    assertThat(resolver.toStored(PARENT + "/my%20folder/a.jpg")).isEqualTo("my folder/a.jpg");
    assertThat(resolver.toStored(resolver.resolve("p1/x y.jpg"))).isEqualTo("p1/x y.jpg");
    assertThat(resolver.toStored("https://cdn.example.com/a.jpg"))
        .isEqualTo("https://cdn.example.com/a.jpg");
  }

  @Test
  void keyOfIgnoresExternalUrls() {
    assertThat(resolver.keyOf("https://cdn.example.com/a.jpg")).isEmpty();
    assertThat(resolver.keyOf("output/images/a.jpg")).contains("a.jpg");
  }

  @Test
  void withoutAParentUrlKeysAreServedAsStored() {
    ImageUrlResolver unset = new ImageUrlResolver(jdbc, "");
    assertThat(unset.resolve("a.jpg")).isEqualTo("a.jpg");
  }

  @Test
  void normalizesParentUrl() {
    assertThat(ImageUrlResolver.normalizeParentUrl(" https://img.example.com/product images/ "))
        .isEqualTo("https://img.example.com/product%20images");
    assertThat(ImageUrlResolver.normalizeParentUrl("https://img.example.com/a%20b"))
        .isEqualTo("https://img.example.com/a%20b");
  }

  @Test
  void rejectsUnsafeParentUrls() {
    for (String bad :
        List.of(
            "",
            "ftp://img.example.com",
            "javascript:alert(1)",
            "/relative",
            "https://img.example.com/x?y=1",
            "https://user:pw@img.example.com")) {
      assertThatThrownBy(() -> ImageUrlResolver.normalizeParentUrl(bad))
          .as(bad)
          .isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void updateStoresTheNormalizedUrl() {
    resolver.update("https://cdn.example.com/imgs/");
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("image_settings"),
            eq("https://cdn.example.com/imgs"));
  }
}
