package com.acme.ecommerce.inventory.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A tiny real HTTP server standing in for auth-service's JWKS endpoint.
 *
 * <p>Byte-for-byte the same pattern as product-service's copy - see that class for the full
 * reasoning. Duplicated rather than shared for the same reason {@code TestKeys} is: this is a test
 * helper, not production code, and each service's test suite owns its own.
 */
public final class StubJwksServer implements AutoCloseable {

  /** Same path auth-service publishes, so the test URL is the production URL. */
  public static final String JWKS_PATH = "/.well-known/jwks.json";

  private final HttpServer server;

  private StubJwksServer(HttpServer server) {
    this.server = server;
  }

  /**
   * Starts a server on an ephemeral port.
   *
   * @param jwksJson body served at {@link #JWKS_PATH}; a {@link Supplier} so a test can rotate keys
   *     without restarting the server
   */
  public static StubJwksServer start(Supplier<String> jwksJson) {
    try {
      HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      httpServer.createContext(
          JWKS_PATH,
          exchange -> {
            byte[] bytes = jwksJson.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          });
      httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      httpServer.start();
      return new StubJwksServer(httpServer);
    } catch (IOException failure) {
      throw new IllegalStateException("Could not start the stub JWKS server", failure);
    }
  }

  /** Absolute URL of the JWKS document. */
  public String jwksUri() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + JWKS_PATH;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
