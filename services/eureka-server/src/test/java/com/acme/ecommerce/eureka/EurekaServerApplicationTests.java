package com.acme.ecommerce.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test for the registry.
 *
 * <p>There is no business logic here to unit-test, so the only thing worth asserting is the thing
 * that actually breaks in practice: that the Eureka server auto-configuration engages, the context
 * boots, and the two surfaces other phases depend on (the actuator health endpoint used by the
 * compose healthcheck, and the dashboard) respond.
 *
 * <p>Uses the JDK {@link HttpClient} rather than a Spring test client so the assertions exercise
 * the real HTTP surface with no framework client in between.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      // `eureka-server` is the docker-compose service name and does not resolve
      // on a developer machine or CI runner.
      "eureka.instance.hostname=localhost",
      // Nothing should be registering during a context test; keep the sweep
      // quiet so it cannot race the assertions.
      "eureka.server.eviction-interval-timer-in-ms=60000"
    })
class EurekaServerApplicationTests {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Autowired private ApplicationContext context;

  @Value("${local.server.port}")
  private int port;

  @Test
  void contextLoadsWithEurekaServerEnabled() {
    // These beans exist only because @EnableEurekaServer imported the server
    // auto-configuration - a plain Boot context would not have them.
    assertThat(context.getBeansOfType(EurekaServerContext.class)).isNotEmpty();
    assertThat(context.getBeansOfType(PeerAwareInstanceRegistry.class)).isNotEmpty();
  }

  @Test
  void actuatorHealthReportsUp() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void dashboardIsServed() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Instances currently registered with Eureka");
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(TIMEOUT)
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
