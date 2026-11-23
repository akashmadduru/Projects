package com.acme.ecommerce.gateway.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/** The fail-loud rules of {@link RouteTable}, on small in-memory YAML fixtures. */
class RouteTableTest {

  private static final String HEAD =
      "spring:\n"
          + "  cloud:\n"
          + "    gateway:\n"
          + "      server:\n"
          + "        webflux:\n"
          + "          routes:\n";

  private static Resource yaml(String text) {
    return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
  }

  /** One route list item at the right indentation; predicates are the raw predicate strings. */
  private static String route(String id, String... predicates) {
    StringBuilder out = new StringBuilder("            - id: " + id + "\n");
    out.append("              uri: lb://X\n");
    if (predicates.length == 0) {
      out.append("              predicates: []\n");
    } else {
      out.append("              predicates:\n");
      for (String predicate : predicates) {
        out.append("                - ").append(predicate).append("\n");
      }
    }
    return out.toString();
  }

  @Test
  void parsesIdsAndCommaSplitPatterns() {
    RouteTable table =
        RouteTable.parse(yaml(HEAD + route("a", "Path=/x,/y/**") + route("b", "Path=/z")));

    assertThat(table.patternsById()).containsEntry("a", List.of("/x", "/y/**"));
    assertThat(table.routeIds()).containsExactly("a", "b");
    assertThat(table.rows()).hasSize(3);
  }

  @Test
  void ignoresTheProfileSpecificDocument() {
    String prodDocument =
        "---\n"
            + "spring:\n"
            + "  config:\n"
            + "    activate:\n"
            + "      on-profile: prod\n"
            + "  cloud:\n"
            + "    gateway:\n"
            + "      server:\n"
            + "        webflux:\n"
            + "          routes:\n"
            + route("prod-only", "Path=/p");

    RouteTable table = RouteTable.parse(yaml(HEAD + route("a", "Path=/x") + prodDocument));

    assertThat(table.routeIds()).containsExactly("a");
  }

  @Test
  void zeroRoutesFails() {
    assertThatThrownBy(() -> RouteTable.parse(yaml("spring:\n  application:\n    name: x\n")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("zero routes");
  }

  @Test
  void routeWithoutPathPredicateFails() {
    assertThatThrownBy(() -> RouteTable.parse(yaml(HEAD + route("nopath"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nopath")
        .hasMessageContaining("no Path predicate");
  }

  @Test
  void nonPathPredicateFailsWithExtendTheContract() {
    assertThatThrownBy(
            () -> RouteTable.parse(yaml(HEAD + route("hosty", "Path=/x", "Host=example.com"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hosty")
        .hasMessageContaining("extend the contract");
  }

  @Test
  void idMismatchWithLiveRoutesFails() {
    RouteTable table = RouteTable.parse(yaml(HEAD + route("a", "Path=/x")));

    table.assertSameIdsAs(List.of("a"));
    assertThatThrownBy(() -> table.assertSameIdsAs(List.of("a", "b")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differ from the live gateway routes");
  }
}
