package com.acme.ecommerce.inventory.support;

import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for every test that needs the real application context.
 *
 * <p>Byte-for-byte the same pattern as product-service's {@code AbstractProductIntegrationTest} -
 * see that class for the full reasoning (Postgres over H2 for schema fidelity, singleton
 * container/stub for wall-clock cost, MockMvc because Boot 4 removed {@code TestRestTemplate}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractInventoryIntegrationTest {

  /** Matches the image used by infra/docker-compose.yml. */
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("inventory_db")
          .withUsername("inventory_service")
          .withPassword("inventory_service_test_pwd");

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
    registry.add("inventory.jwt.jwks-uri", JWKS::jwksUri);
  }

  /** Convenience: an {@code Authorization} header value for a valid VIEWER (read-only) token. */
  protected static String bearerViewer() {
    return "Bearer "
        + KEYS.accessToken(
            "8f14e45f-ceea-4e78-9c39-4f5d2b1a0001", "viewer@example.com", List.of("VIEWER"));
  }

  /** Convenience: an {@code Authorization} header value for a CATALOG_MANAGER token. */
  protected static String bearerCatalogManager() {
    return "Bearer "
        + KEYS.accessToken(
            "8f14e45f-ceea-4e78-9c39-4f5d2b1a0002",
            "manager@example.com",
            List.of("CATALOG_MANAGER"));
  }
}
