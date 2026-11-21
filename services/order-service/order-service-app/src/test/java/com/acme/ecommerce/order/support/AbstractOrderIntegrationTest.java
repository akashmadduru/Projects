package com.acme.ecommerce.order.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for order-service tests that need the real schema (TD-TEST-01). One Postgres per JVM,
 * started in a static initialiser and removed by Ryuk at exit, same as product-service's base.
 * Flyway applies every migration, so these tests also prove the migrations run.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractOrderIntegrationTest {

  @SuppressWarnings("resource") // one container per JVM on purpose; Ryuk removes it at exit
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("order_db")
          .withUsername("order_service")
          .withPassword("order_service_test_pwd");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
