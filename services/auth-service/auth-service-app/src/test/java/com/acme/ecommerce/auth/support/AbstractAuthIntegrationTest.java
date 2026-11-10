package com.acme.ecommerce.auth.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for every test that needs a real database.
 *
 * <p>POSTGRES, NOT H2. The schema depends on {@code citext}, {@code inet}, partial indexes and -
 * critically - on Postgres' READ COMMITTED behaviour when two transactions race the same
 * conditional UPDATE. H2 supports none of that faithfully, so a suite built on it would pass while
 * the production behaviour it claims to prove was broken. The refresh-rotation compare-and-set is
 * exactly the kind of thing an in-memory substitute cannot test.
 *
 * <p>SINGLETON CONTAINER. The container is started once per JVM in a static initialiser and never
 * stopped - Ryuk removes it when the JVM exits. The {@code @Testcontainers}/{@code @Container}
 * annotations would restart it per test class, which on a suite this size costs more wall-clock
 * time than every test in it combined.
 *
 * <p>Flyway runs against the container on first context start, so the migration itself is under
 * test on every single run.
 *
 * <p>HTTP-level tests use {@code MockMvc} rather than a live port. Spring Boot 4 removed {@code
 * TestRestTemplate} (it moved to, and then out of, {@code spring-boot-restclient-test}), and
 * MockMvc exercises the entire {@code DispatcherServlet} + Spring Security filter chain anyway -
 * which is where every behaviour these tests care about lives. It also runs in-process, so there is
 * no port to allocate and no request that can be lost to a socket timeout on a loaded CI box.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractAuthIntegrationTest {

  /** Matches the image used by infra/docker-compose.yml. */
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("auth_db")
          .withUsername("auth_service")
          .withPassword("auth_service_test_pwd");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
