package com.acme.ecommerce.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A tiny real HTTP server standing in for auth-service (and, in the routing tests, for the
 * downstream generally).
 *
 * <p>Uses the JDK's {@code com.sun.net.httpserver} rather than adding WireMock or Testcontainers.
 * Two reasons, in order of importance: the assertions here are about the GATEWAY's behaviour, and
 * every dependency added to prove that is a dependency the gateway's build has to keep resolving;
 * and a real socket on a real port exercises the actual Netty client path, connection handling and
 * timeouts, which an in-process mock would bypass entirely.
 *
 * <p>Two capabilities the tests actually need:
 *
 * <ul>
 *   <li><b>Header echo</b> - {@code /echo} replies with the request headers it received, which is
 *       the only honest way to assert "a client-supplied X-User-Id never reaches downstream".
 *   <li><b>Controllable failure</b> - {@link #setAvailable(boolean)} makes the server refuse to
 *       answer, so circuit-breaker fallback behaviour can be observed rather than assumed.
 * </ul>
 */
public final class StubHttpServer implements AutoCloseable {

  private final HttpServer server;
  private final AtomicBoolean available = new AtomicBoolean(true);

  private StubHttpServer(HttpServer server) {
    this.server = server;
  }

  /**
   * Starts a server on an ephemeral port.
   *
   * @param jwksJson body served at {@code /.well-known/jwks.json}
   */
  public static StubHttpServer start(Supplier<String> jwksJson) {
    try {
      HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      StubHttpServer stub = new StubHttpServer(httpServer);

      httpServer.createContext(
          "/.well-known/jwks.json",
          exchange -> stub.respond(exchange, 200, "application/json", jwksJson.get()));

      // Everything else echoes. A single catch-all context keeps the stub honest about which
      // path the gateway actually proxied, because the echo body includes it.
      httpServer.createContext("/", stub::echo);

      httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      httpServer.start();
      return stub;
    } catch (IOException failure) {
      throw new IllegalStateException("Could not start the stub HTTP server", failure);
    }
  }

  /** The port the stub is listening on. */
  public int port() {
    return server.getAddress().getPort();
  }

  /** Base URL of the stub. */
  public String baseUrl() {
    return "http://127.0.0.1:" + port();
  }

  /**
   * Simulates the downstream being sick.
   *
   * <p>Answers 503 rather than closing the socket: a refused connection and a failing service are
   * different failure modes, and 503-with-a-body is the one that proves the gateway does not leak
   * the downstream's body through its own fallback.
   */
  public void setAvailable(boolean value) {
    available.set(value);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void echo(HttpExchange exchange) throws IOException {
    if (!available.get()) {
      respond(exchange, 503, "text/plain", "downstream is intentionally unavailable");
      return;
    }

    // Behaves like auth-service does in reality: it has its own CorsConfigurationSource (it must -
    // it is directly reachable in dev) and therefore returns its own Access-Control-* headers on a
    // cross-origin request. Without this the stub is politer than any real downstream and the
    // duplicate-header bug the gateway's DedupeResponseHeader filter exists to fix is invisible to
    // the test suite. It was found by curling the live stack, which is one time too late.
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
      exchange.getResponseHeaders().set("Vary", "Origin");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("path", exchange.getRequestURI().getPath());
    body.put("method", exchange.getRequestMethod());

    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (name, values) ->
                headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
    body.put("headers", headers);

    respond(exchange, 200, "application/json", toJson(body));
  }

  private void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /**
   * Minimal JSON writer.
   *
   * <p>Hand-rolled rather than pulling Jackson into the stub: the shape is fixed and shallow, and
   * the alternative is a dependency whose serialisation settings would then quietly influence what
   * the test observes.
   */
  private static String toJson(Map<String, Object> value) {
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : value.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append(quote(entry.getKey())).append(':');
      Object entryValue = entry.getValue();
      if (entryValue instanceof Map<?, ?> nested) {
        Map<String, Object> copy = new LinkedHashMap<>();
        nested.forEach((k, v) -> copy.put(String.valueOf(k), v));
        json.append(toJson(copy));
      } else {
        json.append(quote(String.valueOf(entryValue)));
      }
    }
    return json.append('}').toString();
  }

  private static String quote(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}
