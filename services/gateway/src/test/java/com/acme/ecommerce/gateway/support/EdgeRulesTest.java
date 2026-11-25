package com.acme.ecommerce.gateway.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.gateway.support.EdgeRules.Decision;
import com.acme.ecommerce.gateway.support.EdgeRules.Entry;
import com.acme.ecommerce.gateway.support.EdgeRules.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The self-check and first-match logic of {@link EdgeRules}, on hand-built stub tables. */
class EdgeRulesTest {

  private static final Decision PERMIT = new Decision(Kind.PERMIT, Set.of());
  private static final Decision AUTHENTICATED = new Decision(Kind.AUTHENTICATED, Set.of());
  private static final Decision DENY = new Decision(Kind.DENY, Set.of());
  private static final Decision ROLE = new Decision(Kind.ROLE, Set.of("ROLE_ADMIN"));

  private static List<Entry> goodTable() {
    List<Entry> table = new ArrayList<>();
    table.add(new Entry("ANY", "/actuator/**", DENY, false));
    table.add(new Entry("ANY", "/api/auth/login", PERMIT, false));
    table.add(new Entry("GET", "/api/sellers/me", AUTHENTICATED, false));
    table.add(new Entry("GET", "/api/sellers/*", PERMIT, false));
    table.add(new Entry("ANY", "/api/admin/**", ROLE, false));
    table.add(new Entry("ANY", "<any exchange>", AUTHENTICATED, true));
    return table;
  }

  @Test
  void aWellFormedTablePassesTheSelfCheck() {
    assertThatCode(() -> new EdgeRules(goodTable()).selfCheck(List.of("/api/auth/login")))
        .doesNotThrowAnyException();
  }

  @Test
  void anEmptyTableFailsTheSelfCheck() {
    assertThatThrownBy(() -> new EdgeRules(List.of()).selfCheck(List.of("/api/auth/login")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EdgeRules could not read the chain");
  }

  @Test
  void aPublicPathThatIsNotAPermitFailsTheSelfCheck() {
    assertThatThrownBy(
            () -> new EdgeRules(goodTable()).selfCheck(List.of("/api/auth/login", "/oauth2/**")))
        .hasMessageContaining("EdgeRules could not read the chain")
        .hasMessageContaining("/oauth2/**");
  }

  @Test
  void aMissingAnchorFailsTheSelfCheck() {
    List<Entry> noActuator = new ArrayList<>(goodTable());
    noActuator.remove(0);
    assertThatThrownBy(() -> new EdgeRules(noActuator).selfCheck(List.of("/api/auth/login")))
        .hasMessageContaining("/actuator/**");

    List<Entry> noAdmin = new ArrayList<>(goodTable());
    noAdmin.remove(4);
    assertThatThrownBy(() -> new EdgeRules(noAdmin).selfCheck(List.of("/api/auth/login")))
        .hasMessageContaining("/api/admin/**");
  }

  @Test
  void aLastEntryThatIsNotTheAuthenticatedCatchAllFailsTheSelfCheck() {
    List<Entry> permissiveTail = new ArrayList<>(goodTable());
    permissiveTail.set(5, new Entry("ANY", "<any exchange>", PERMIT, true));
    assertThatThrownBy(() -> new EdgeRules(permissiveTail).selfCheck(List.of("/api/auth/login")))
        .hasMessageContaining("catch-all AUTHENTICATED");

    List<Entry> noCatchAll = new ArrayList<>(goodTable());
    noCatchAll.remove(5);
    assertThatThrownBy(() -> new EdgeRules(noCatchAll).selfCheck(List.of("/api/auth/login")))
        .hasMessageContaining("catch-all AUTHENTICATED");
  }

  @Test
  void classifyIsFirstMatchAndMethodAware() {
    EdgeRules rules = new EdgeRules(goodTable());

    assertThat(rules.classify("GET", "/api/sellers/me").decision()).isEqualTo(AUTHENTICATED);
    assertThat(rules.classify("GET", "/api/sellers/1").decision()).isEqualTo(PERMIT);
    // Method-bound: POST does not match the GET permit and falls through to the catch-all.
    assertThat(rules.classify("POST", "/api/sellers/1").decision()).isEqualTo(AUTHENTICATED);
    assertThat(rules.classify("DELETE", "/api/admin/x").decision()).isEqualTo(ROLE);
    assertThat(rules.classify("GET", "/actuator/env").decision()).isEqualTo(DENY);
    assertThat(rules.classify("GET", "/nothing/here").catchAll()).isTrue();
  }
}
