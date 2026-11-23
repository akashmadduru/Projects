package com.acme.ecommerce.gateway.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * The gateway's routes as written in {@code application.yml}, never as a hand-kept list.
 *
 * <p>Loads the YAML with Boot's own {@link YamlPropertySourceLoader}, keeps the default document
 * (the one without {@code spring.config.activate.on-profile}), and binds the routes key to {@link
 * RouteDefinition} with {@link Binder}, which is the binding Spring Cloud Gateway itself uses. So
 * the comma split of {@code Path=a,b} is the framework's, not ours.
 *
 * <p>Fails loudly: zero routes, a route without a {@code Path} predicate and any non-{@code Path}
 * predicate are errors, so a route shape this contract does not understand cannot be silently
 * skipped.
 */
public final class RouteTable {

  /** The routes key, same as {@code GatewayProperties}' prefix plus {@code routes}. */
  static final String ROUTES_KEY = "spring.cloud.gateway.server.webflux.routes";

  /** One route: its id and every {@code Path=} pattern, in file order. */
  public record Route(String id, List<String> patterns) {}

  private final List<Route> routes;

  private RouteTable(List<Route> routes) {
    this.routes = List.copyOf(routes);
  }

  /** Parses the main {@code application.yml} from the classpath. */
  public static RouteTable load() {
    return parse(new ClassPathResource("application.yml"));
  }

  /** Parses any YAML resource; used with in-memory fixtures to test the fail-loud rules. */
  public static RouteTable parse(Resource yaml) {
    List<PropertySource<?>> documents;
    try {
      documents = new YamlPropertySourceLoader().load("route-table", yaml);
    } catch (IOException failure) {
      throw new IllegalStateException("RouteTable could not read " + yaml, failure);
    }
    PropertySource<?> defaults =
        documents.stream()
            .filter(d -> !d.containsProperty("spring.config.activate.on-profile"))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("RouteTable found no default YAML document"));

    List<RouteDefinition> definitions =
        new Binder(ConfigurationPropertySources.from(defaults))
            .bind(ROUTES_KEY, Bindable.listOf(RouteDefinition.class))
            .orElse(List.of());
    if (definitions.isEmpty()) {
      throw new IllegalStateException("RouteTable found zero routes under " + ROUTES_KEY);
    }

    List<Route> parsed = new ArrayList<>();
    for (RouteDefinition definition : definitions) {
      List<String> patterns = new ArrayList<>();
      definition
          .getPredicates()
          .forEach(
              predicate -> {
                if (!"Path".equals(predicate.getName())) {
                  throw new IllegalStateException(
                      "RouteTable: route '"
                          + definition.getId()
                          + "' has a "
                          + predicate.getName()
                          + " predicate; extend the contract to handle it");
                }
                patterns.addAll(predicate.getArgs().values());
              });
      if (patterns.isEmpty()) {
        throw new IllegalStateException(
            "RouteTable: route '" + definition.getId() + "' has no Path predicate");
      }
      parsed.add(new Route(definition.getId(), patterns));
    }
    return new RouteTable(parsed);
  }

  public List<Route> routes() {
    return routes;
  }

  public List<String> routeIds() {
    return routes.stream().map(Route::id).toList();
  }

  /** Route id to its patterns, in file order. */
  public Map<String, List<String>> patternsById() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    routes.forEach(r -> out.put(r.id(), r.patterns()));
    return out;
  }

  /** Every pattern of every route as (routeId, pattern) rows, in file order. */
  public List<Map.Entry<String, String>> rows() {
    List<Map.Entry<String, String>> out = new ArrayList<>();
    for (Route route : routes) {
      route.patterns().forEach(p -> out.add(Map.entry(route.id(), p)));
    }
    return out;
  }

  /** Fails unless the parsed ids equal the live {@code GatewayProperties} route ids. */
  public void assertSameIdsAs(Collection<String> liveIds) {
    var parsedIds = new TreeSet<>(routeIds());
    var live = new TreeSet<>(liveIds);
    if (!parsedIds.equals(live)) {
      throw new IllegalStateException(
          "RouteTable ids " + parsedIds + " differ from the live gateway routes " + live);
    }
  }
}
