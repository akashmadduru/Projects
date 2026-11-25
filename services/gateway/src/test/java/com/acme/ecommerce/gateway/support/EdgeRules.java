package com.acme.ecommerce.gateway.support;

import com.acme.ecommerce.common.security.Roles;
import com.acme.ecommerce.gateway.config.SecurityConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

/**
 * The gateway's REAL authorization rules, read back out of the {@link SecurityWebFilterChain} bean.
 *
 * <p>Not a copy of {@code SecurityConfig}: the ordered matcher list is taken from the {@code
 * AuthorizationWebFilter} that actually runs, and each rule's decision is obtained by invoking the
 * rule's own {@link ReactiveAuthorizationManager} with an anonymous, a CUSTOMER and each privileged
 * authority. Coupled to Spring Security 7.0.x internals (field names below); the {@link #selfCheck}
 * fails loudly ("EdgeRules could not read the chain") rather than returning a table that is
 * silently empty or wrong.
 *
 * <p>Spring Security 7.0.6 names, recorded by the Task 1 spike: {@code AuthorizationWebFilter
 * .authorizationManager} (an {@code ObservationReactiveAuthorizationManager} whose {@code delegate}
 * is a {@code DelegatingReactiveAuthorizationManager}); its {@code mappings} is a {@code
 * List<ServerWebExchangeMatcherEntry>} with {@code matcher} and {@code entry}; {@code
 * OrServerWebExchangeMatcher.matchers}; {@code PathPatternParserServerWebExchangeMatcher.pattern}
 * and {@code .method}. The manager method is {@code authorize} (7.x; {@code check} is deprecated).
 */
public final class EdgeRules {

  /** What a rule does to a request. */
  public enum Kind {
    PERMIT,
    DENY,
    AUTHENTICATED,
    ROLE
  }

  /** The decision of one rule. {@code roles} is only populated for {@link Kind#ROLE}. */
  public record Decision(Kind kind, Set<String> roles) {
    @Override
    public String toString() {
      return kind == Kind.ROLE ? "ROLE" + roles : kind.name();
    }
  }

  /**
   * One ordered rule. {@code method} is {@code ANY} or an HTTP method name; {@code catchAll} marks
   * the final {@code anyExchange()} rule, whose pattern is {@code "<any exchange>"}.
   */
  public record Entry(String method, String pattern, Decision decision, boolean catchAll) {
    boolean matches(String requestMethod, String path) {
      if (catchAll) {
        return true;
      }
      if (!ANY.equals(method) && !method.equalsIgnoreCase(requestMethod)) {
        return false;
      }
      return PathPatternHolder.parse(pattern).matches(PathContainer.parsePath(path));
    }

    @Override
    public String toString() {
      return method + " " + pattern + " -> " + decision;
    }
  }

  public static final String ANY = "ANY";

  /** Authorities probed to discover which roles a rule admits (CUSTOMER is probed separately). */
  private static final List<String> PRIVILEGED =
      List.of(
          Roles.ROLE_ADMIN,
          Roles.ROLE_SUPPORT,
          Roles.ROLE_FINANCE,
          Roles.ROLE_CATALOG_MANAGER,
          Roles.ROLE_SELLER,
          Roles.ROLE_VIEWER);

  private final List<Entry> entries;

  public EdgeRules(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** Reads the real chain and runs the self-check against the real {@code PUBLIC_PATHS}. */
  public static EdgeRules read(SecurityWebFilterChain chain) {
    EdgeRules rules = new EdgeRules(readEntries(chain));
    rules.selfCheck(publicPaths());
    return rules;
  }

  /** {@code SecurityConfig.PUBLIC_PATHS}, reflected from the private static field. */
  public static List<String> publicPaths() {
    Object value = ReflectionTestUtils.getField(SecurityConfig.class, "PUBLIC_PATHS");
    if (!(value instanceof String[] paths) || paths.length == 0) {
      throw new IllegalStateException("EdgeRules could not read the chain: PUBLIC_PATHS is empty");
    }
    return Arrays.asList(paths);
  }

  public List<Entry> entries() {
    return entries;
  }

  /** The first rule that matches, which is exactly how the real chain decides. */
  public Entry classify(String method, String path) {
    for (Entry entry : entries) {
      if (entry.matches(method, path)) {
        return entry;
      }
    }
    throw new IllegalStateException("EdgeRules could not read the chain: no rule matches " + path);
  }

  /** Every PERMIT rule, in chain order. */
  public List<Entry> permits() {
    return entries.stream().filter(e -> e.decision().kind() == Kind.PERMIT).toList();
  }

  /** Every ROLE rule, in chain order. */
  public List<Entry> roleRules() {
    return entries.stream().filter(e -> e.decision().kind() == Kind.ROLE).toList();
  }

  /**
   * Fails unless the table has the known anchors, so a Spring Security upgrade that changes the
   * internals cannot make the contract pass on an empty or mis-read table.
   */
  public void selfCheck(Collection<String> publicPaths) {
    String prefix = "EdgeRules could not read the chain: ";
    if (entries.isEmpty()) {
      throw new IllegalStateException(prefix + "no entries");
    }
    if (publicPaths.isEmpty()) {
      throw new IllegalStateException(prefix + "PUBLIC_PATHS is empty");
    }
    for (String path : publicPaths) {
      boolean found =
          entries.stream()
              .anyMatch(
                  e ->
                      !e.catchAll()
                          && ANY.equals(e.method())
                          && e.pattern().equals(path)
                          && e.decision().kind() == Kind.PERMIT);
      if (!found) {
        throw new IllegalStateException(
            prefix + "PUBLIC_PATHS entry " + path + " is not an ANY PERMIT");
      }
    }
    requireKind("/actuator/**", Kind.DENY, prefix);
    requireKind("/api/admin/**", Kind.ROLE, prefix);
    Entry last = entries.get(entries.size() - 1);
    if (!last.catchAll() || last.decision().kind() != Kind.AUTHENTICATED) {
      throw new IllegalStateException(
          prefix + "last entry is not the catch-all AUTHENTICATED: " + last);
    }
  }

  private void requireKind(String pattern, Kind kind, String prefix) {
    boolean ok =
        entries.stream()
            .anyMatch(
                e -> !e.catchAll() && e.pattern().equals(pattern) && e.decision().kind() == kind);
    if (!ok) {
      throw new IllegalStateException(prefix + pattern + " is not " + kind);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reading the chain.
  // ---------------------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static List<Entry> readEntries(SecurityWebFilterChain chain) {
    String prefix = "EdgeRules could not read the chain: ";
    List<org.springframework.web.server.WebFilter> filters =
        chain.getWebFilters().collectList().block();
    Object authorizationFilter =
        filters == null
            ? null
            : filters.stream()
                .filter(AuthorizationWebFilter.class::isInstance)
                .findFirst()
                .orElse(null);
    if (authorizationFilter == null) {
      throw new IllegalStateException(prefix + "no AuthorizationWebFilter");
    }
    Object manager = field(authorizationFilter, "authorizationManager");
    // Unwrap observation decorators until the delegating manager (the one with `mappings`).
    for (int depth = 0; depth < 6 && manager != null && !hasField(manager, "mappings"); depth++) {
      manager = field(manager, "delegate");
    }
    if (manager == null) {
      throw new IllegalStateException(prefix + "no DelegatingReactiveAuthorizationManager");
    }
    List<?> mappings = (List<?>) field(manager, "mappings");
    List<Entry> out = new ArrayList<>();
    for (int index = 0; index < mappings.size(); index++) {
      Object mapping = mappings.get(index);
      Object matcher = field(mapping, "matcher");
      ReactiveAuthorizationManager<AuthorizationContext> rule =
          (ReactiveAuthorizationManager<AuthorizationContext>) field(mapping, "entry");
      Decision decision = decide(rule);
      requireSupportedMatcher(matcher, index, mappings.size() - 1);
      if (matcher instanceof OrServerWebExchangeMatcher or) {
        for (Object single : (List<?>) field(or, "matchers")) {
          PathPattern pattern = (PathPattern) field(single, "pattern");
          HttpMethod method = (HttpMethod) field(single, "method");
          out.add(
              new Entry(
                  method == null ? ANY : method.name(),
                  pattern.getPatternString(),
                  decision,
                  false));
        }
      } else {
        out.add(new Entry(ANY, "<any exchange>", decision, true));
      }
    }
    return out;
  }

  /**
   * Only {@code OrServerWebExchangeMatcher} (pathMatchers) entries are understood, plus the {@code
   * anyExchange} matcher, which must be last. Anything else is a rule shape this contract cannot
   * classify, so it fails loudly instead of being read as a catch-all.
   */
  static void requireSupportedMatcher(Object matcher, int index, int lastIndex) {
    if (!(matcher instanceof OrServerWebExchangeMatcher) && index != lastIndex) {
      throw new IllegalStateException(
          "EdgeRules could not read the chain: unsupported matcher "
              + (matcher == null ? "null" : matcher.getClass().getName())
              + " at index "
              + index);
    }
  }

  private static Decision decide(ReactiveAuthorizationManager<AuthorizationContext> rule) {
    if (granted(rule, Mono.empty())) {
      return new Decision(Kind.PERMIT, Set.of());
    }
    if (granted(rule, Mono.just(auth("ROLE_CUSTOMER")))) {
      return new Decision(Kind.AUTHENTICATED, Set.of());
    }
    Set<String> admitted = new TreeSet<>();
    for (String authority : PRIVILEGED) {
      if (granted(rule, Mono.just(auth(authority)))) {
        admitted.add(authority);
      }
    }
    return admitted.isEmpty()
        ? new Decision(Kind.DENY, Set.of())
        : new Decision(Kind.ROLE, new LinkedHashSet<>(admitted));
  }

  private static Authentication auth(String authority) {
    return new TestingAuthenticationToken("probe", "n/a", authority);
  }

  private static boolean granted(
      ReactiveAuthorizationManager<AuthorizationContext> rule,
      Mono<Authentication> authentication) {
    ServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
    var result = rule.authorize(authentication, new AuthorizationContext(exchange)).block();
    return result != null && result.isGranted();
  }

  private static Object field(Object target, String name) {
    Class<?> type = target.getClass();
    while (type != null) {
      for (Field f : type.getDeclaredFields()) {
        if (f.getName().equals(name)) {
          return ReflectionTestUtils.getField(target, name);
        }
      }
      type = type.getSuperclass();
    }
    throw new IllegalStateException(
        "EdgeRules could not read the chain: "
            + target.getClass().getName()
            + " has no field '"
            + name
            + "'");
  }

  private static boolean hasField(Object target, String name) {
    Class<?> type = target.getClass();
    while (type != null) {
      for (Field f : type.getDeclaredFields()) {
        if (f.getName().equals(name)) {
          return true;
        }
      }
      type = type.getSuperclass();
    }
    return false;
  }

  /** Compiled-pattern cache using the same parser Spring's {@code pathMatchers} uses. */
  private static final class PathPatternHolder {
    private static final org.springframework.web.util.pattern.PathPatternParser PARSER =
        new org.springframework.web.util.pattern.PathPatternParser();

    static PathPattern parse(String pattern) {
      return PARSER.parse(pattern);
    }
  }
}
