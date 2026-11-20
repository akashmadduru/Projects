package com.acme.ecommerce.auth.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.ecommerce.auth.admin.AdminUserDtos.TargetStatus;
import com.acme.ecommerce.auth.cache.AccessTokenDenyStore;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.auth.token.RevokeReason;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.security.Roles;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The deny-list write on disable and demote: after commit only, never on rollback or refusal. */
class AdminUserServiceDenyListTest extends AbstractAuthIntegrationTest {

  @Autowired private AdminUserService admin;
  @Autowired private UserFixtures fixtures;
  @Autowired private DatabaseCleaner cleaner;
  @Autowired private PlatformTransactionManager txManager;
  @MockitoBean private AccessTokenDenyStore denyStore;
  @MockitoSpyBean private TokenService tokens;

  private UserAccount adminA;
  private UserAccount adminB;
  private UserAccount viewer;

  @BeforeEach
  void setUp() {
    cleaner.clean();
    Mockito.reset(denyStore, tokens);
    adminA = fixtures.createPasswordUser("a@example.com", "Password123!", Roles.ROLE_ADMIN);
    adminB = fixtures.createPasswordUser("b@example.com", "Password123!", Roles.ROLE_ADMIN);
    viewer = fixtures.createPasswordUser("v@example.com", "Password123!", Roles.ROLE_VIEWER);
  }

  @Test
  @DisplayName("disable writes the deny entry after commit and still revokes refresh tokens")
  void disableWritesAfterCommit() {
    admin.changeStatus(adminA.getId(), viewer.getId(), TargetStatus.DISABLED);

    verify(denyStore).revokeIssuedUpTo(viewer.getId());
    verify(tokens).revokeAllSessions(viewer.getId(), RevokeReason.ADMIN);
  }

  @Test
  @DisplayName("the write is deferred until the surrounding transaction commits")
  void writeWaitsForCommit() {
    new TransactionTemplate(txManager)
        .executeWithoutResult(
            s -> {
              admin.changeStatus(adminA.getId(), viewer.getId(), TargetStatus.DISABLED);
              verifyNoInteractions(denyStore);
            });

    verify(denyStore).revokeIssuedUpTo(viewer.getId());
  }

  @Test
  @DisplayName("a rollback writes nothing")
  void rollbackWritesNothing() {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        s -> {
                          admin.changeStatus(adminA.getId(), viewer.getId(), TargetStatus.DISABLED);
                          throw new IllegalStateException("boom");
                        }))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(denyStore);
  }

  @Test
  @DisplayName("demote with a role removed writes the deny entry and revokes refresh tokens")
  void demoteWithRemovalWrites() {
    admin.changeRoles(adminA.getId(), adminB.getId(), Set.of("VIEWER"));

    verify(denyStore).revokeIssuedUpTo(adminB.getId());
    verify(tokens).revokeAllSessions(adminB.getId(), RevokeReason.ADMIN);
  }

  @Test
  @DisplayName("a role change that removes nothing writes nothing")
  void noRemovalWritesNothing() {
    admin.changeRoles(adminA.getId(), viewer.getId(), Set.of("VIEWER", "SUPPORT"));

    verifyNoInteractions(denyStore);
    verify(tokens, never()).revokeAllSessions(any(), any());
  }

  @Test
  @DisplayName("SELF_MODIFICATION and LAST_ADMIN write nothing")
  void refusalsWriteNothing() {
    assertThatThrownBy(
            () -> admin.changeStatus(adminA.getId(), adminA.getId(), TargetStatus.DISABLED))
        .isInstanceOf(DomainRuleViolationException.class);
    admin.changeRoles(adminA.getId(), adminB.getId(), Set.of("VIEWER"));
    Mockito.reset(denyStore);
    assertThatThrownBy(
            () -> admin.changeStatus(viewer.getId(), adminA.getId(), TargetStatus.DISABLED))
        .isInstanceOf(DomainRuleViolationException.class);
    assertThatThrownBy(() -> admin.changeRoles(viewer.getId(), adminA.getId(), Set.of("VIEWER")))
        .isInstanceOf(DomainRuleViolationException.class);

    verifyNoInteractions(denyStore);
  }

  @Test
  @DisplayName("enable makes no deny-store call (the entry is never deleted)")
  void enableTouchesNothing() {
    fixtures.disable(viewer.getId());

    admin.changeStatus(adminA.getId(), viewer.getId(), TargetStatus.ACTIVE);

    verifyNoInteractions(denyStore);
    verify(denyStore, never()).revokeIssuedUpTo(eq(viewer.getId()));
  }
}
