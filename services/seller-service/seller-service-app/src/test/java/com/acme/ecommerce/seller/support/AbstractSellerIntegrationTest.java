package com.acme.ecommerce.seller.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real-schema base for seller-service tests (money paths); one Postgres per JVM. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractSellerIntegrationTest {

  @SuppressWarnings("resource") // one container per JVM on purpose; Ryuk removes it at exit
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("seller_db")
          .withUsername("seller_service")
          .withPassword("seller_service_test_pwd");

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
