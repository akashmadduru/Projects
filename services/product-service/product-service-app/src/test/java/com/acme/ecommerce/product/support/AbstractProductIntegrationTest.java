package com.acme.ecommerce.product.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for every test that needs the real application context.
 *
 * <p>POSTGRES, NOT H2, for the same reason auth-service made that call: the migration under test
 * uses {@code pgcrypto}, {@code char(3)}, a regex CHECK constraint and {@code numeric(19,2)}. H2
 * supports none of that faithfully, so a suite built on it would pass while proving nothing about
 * the schema that actually ships. Flyway runs against the container on first context start, which
 * means the migration - including its seed rows - is under test on every single run.
 *
 * <p>SINGLETON CONTAINER AND SINGLETON STUB. Both are started once per JVM in a static initialiser
 * and never stopped; Ryuk removes the container when the JVM exits. The {@code @Testcontainers}/
 * {@code @Container} annotations would restart the container per test class, which costs more
 * wall-clock time than every test in the suite combined.
 *
 * <p>ONE SIGNING KEY FOR THE WHOLE JVM, served over real HTTP by {@link StubJwksServer}. The
 * decoder therefore does a genuine JWKS fetch and a genuine RSA signature verification - the exact
 * behaviour a mocked {@code JwtDecoder} would have assumed rather than proved.
 *
 * <p>HTTP-level tests use {@code MockMvc} rather than a live port: Spring Boot 4 removed {@code
 * TestRestTemplate}, and MockMvc exercises the whole {@code DispatcherServlet} + Spring Security
 * filter chain in-process, which is where every behaviour these tests care about lives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractProductIntegrationTest {

  /** Matches the image used by infra/docker-compose.yml. */
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("product_db")
          .withUsername("product_service")
          .withPassword("product_service_test_pwd");

  /** The platform's signing key, for the duration of this JVM. */
  protected static final TestKeys KEYS = TestKeys.generate("test-key-1");

  private static final StubJwksServer JWKS = StubJwksServer.start(KEYS::jwksJson);

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // The static escape hatch. There is no Eureka registry in a test JVM, so
    // `lb://AUTH-SERVICE/...` would have nothing to resolve.
    registry.add("product.jwt.jwks-uri", JWKS::jwksUri);
  }

  /** Convenience: an {@code Authorization} header value for a valid VIEWER token. */
  protected static String bearerViewer() {
    return "Bearer "
        + KEYS.accessToken(
            "8f14e45f-ceea-4e78-9c39-4f5d2b1a0001",
            "viewer@example.com",
            java.util.List.of("VIEWER"));
  }

  /** Convenience: an {@code Authorization} header value for a CATALOG_MANAGER token. */
  protected static String bearerCatalogManager() {
    return "Bearer "
        + KEYS.accessToken(
            "8f14e45f-ceea-4e78-9c39-4f5d2b1a0002",
            "manager@example.com",
            java.util.List.of("CATALOG_MANAGER"));
  }
}
