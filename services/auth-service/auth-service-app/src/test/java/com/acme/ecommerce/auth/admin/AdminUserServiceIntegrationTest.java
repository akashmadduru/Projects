package com.acme.ecommerce.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.auth.admin.AdminUserDtos.TargetStatus;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.security.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Admin privilege rules against the real database (TD-ADM-01): no self-modification, never zero
 * active admins (including two concurrent demotions), and sessions revoked on disable or role loss.
 */
class AdminUserServiceIntegrationTest extends AbstractAuthIntegrationTest {

  @Autowired private AdminUserService admin;
  @Autowired private UserFixtures fixtures;
  @Autowired private DatabaseCleaner cleaner;
  @MockitoSpyBean private TokenService tokens;

  private UserAccount adminA;
  private UserAccount adminB;
  private UserAccount viewer;

  @BeforeEach
  void setUp() {
    cleaner.clean();
    adminA = fixtures.createPasswordUser("a@example.com", "Password123!", Roles.ROLE_ADMIN);
    adminB = fixtures.createPasswordUser("b@example.com", "Password123!", Roles.ROLE_ADMIN);
    viewer = fixtures.createPasswordUser("v@example.com", "Password123!", Roles.ROLE_VIEWER);
  }

  private static String rule(Throwable e) {
    return String.valueOf(((DomainRuleViolationException) e).details().get("rule"));
  }

  @Test
  @DisplayName("an admin cannot change their own status or roles")
  void noSelfModification() {
    assertThatThrownBy(
            () -> admin.changeStatus(adminA.getId(), adminA.getId(), TargetStatus.DISABLED))
        .isInstanceOf(DomainRuleViolationException.class)
        .satisfies(e -> assertThat(rule(e)).isEqualTo("SELF_MODIFICATION"));
    assertThatThrownBy(() -> admin.changeRoles(adminA.getId(), adminA.getId(), Set.of("VIEWER")))
        .satisfies(e -> assertThat(rule(e)).isEqualTo("SELF_MODIFICATION"));
  }

  @Test
  @DisplayName("the last active admin can be neither disabled nor demoted")
  void lastAdminIsProtected() {
    admin.changeRoles(adminA.getId(), adminB.getId(), Set.of("VIEWER"));

    assertThatThrownBy(
            () -> admin.changeStatus(viewer.getId(), adminA.getId(), TargetStatus.DISABLED))
        .satisfies(e -> assertThat(rule(e)).isEqualTo("LAST_ADMIN"));
    assertThatThrownBy(() -> admin.changeRoles(viewer.getId(), adminA.getId(), Set.of("VIEWER")))
        .satisfies(e -> assertThat(rule(e)).isEqualTo("LAST_ADMIN"));
    assertThat(admin.get(adminA.getId()).roles()).contains("ADMIN");
  }

  @Test
  @DisplayName("two admins demoting each other at the same time: exactly one succeeds")
  void concurrentDemotionsLeaveOneAdmin() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> calls =
        List.of(
            () -> {
              start.await();
              admin.changeRoles(adminB.getId(), adminA.getId(), Set.of("VIEWER"));
              return true;
            },
            () -> {
              start.await();
              admin.changeRoles(adminA.getId(), adminB.getId(), Set.of("VIEWER"));
              return true;
            });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (Callable<Boolean> c : calls) {
        futures.add(pool.submit(c));
      }
      start.countDown();
      int ok = 0;
      int lastAdmin = 0;
      for (Future<Boolean> f : futures) {
        try {
          f.get();
          ok++;
        } catch (java.util.concurrent.ExecutionException e) {
          assertThat(rule(e.getCause())).isEqualTo("LAST_ADMIN");
          lastAdmin++;
        }
      }
      assertThat(ok).isEqualTo(1);
      assertThat(lastAdmin).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    long admins =
        List.of(adminA.getId(), adminB.getId()).stream()
            .filter(id -> admin.get(id).roles().contains("ADMIN"))
            .count();
    assertThat(admins).isEqualTo(1);
  }

  @Test
  @DisplayName("disabling a user revokes all their sessions")
  void disableRevokesSessions() {
    admin.changeStatus(adminA.getId(), viewer.getId(), TargetStatus.DISABLED);

    assertThat(admin.get(viewer.getId()).status()).isEqualTo("DISABLED");
    verify(tokens).revokeAllSessions(eq(viewer.getId()), any());
  }

  @Test
  @DisplayName("removing a role revokes sessions; only adding one does not")
  void roleChangesAndSessions() {
    UUID v = viewer.getId();

    admin.changeRoles(adminA.getId(), v, Set.of("VIEWER", "SUPPORT"));
    verify(tokens, never()).revokeAllSessions(eq(v), any());
    assertThat(admin.get(v).roles()).containsExactlyInAnyOrder("VIEWER", "SUPPORT");

    admin.changeRoles(adminA.getId(), v, Set.of("VIEWER"));
    verify(tokens).revokeAllSessions(eq(v), any());
    assertThat(admin.get(v).roles()).containsExactly("VIEWER");
  }
}
