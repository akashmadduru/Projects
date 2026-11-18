package com.acme.ecommerce.cart.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real-schema base for cart-service tests (TD-TEST-01); one Postgres per JVM. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractCartIntegrationTest {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("cart_db")
          .withUsername("cart_service")
          .withPassword("cart_service_test_pwd");

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
