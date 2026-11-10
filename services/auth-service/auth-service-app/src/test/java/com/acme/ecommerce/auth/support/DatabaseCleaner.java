package com.acme.ecommerce.auth.support;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Truncates the mutable tables between tests.
 *
 * <p>NOT {@code @Transactional} rollback on the test method. Large parts of this service
 * deliberately run in their own transactions ({@code REQUIRES_NEW} on the refresh-token state
 * changes and the lockout counters, precisely so they survive the exception thrown right after
 * them). A rolled-back test transaction would never see those writes, so the tests that matter most
 * would silently assert nothing.
 *
 * <p>{@code roles} is excluded: it is reference data seeded by the migration, and deleting it would
 * break every subsequent test in the JVM.
 */
@Component
public class DatabaseCleaner {

  private final EntityManager entityManager;

  public DatabaseCleaner(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Wipes users and everything that cascades from them. */
  @Transactional
  public void clean() {
    entityManager
        .createNativeQuery(
            "TRUNCATE auth.refresh_tokens, auth.federated_identities, "
                + "auth.user_credentials, auth.user_roles, auth.users CASCADE")
        .executeUpdate();
  }
}
