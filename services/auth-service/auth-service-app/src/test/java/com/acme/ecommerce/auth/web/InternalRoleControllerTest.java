package com.acme.ecommerce.auth.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TD-P9-09: the seller role can be revoked internally, and nothing else can. */
class InternalRoleControllerTest {

  private final UUID userId = UUID.randomUUID();
  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final RoleRepository roles = mock(RoleRepository.class);
  private final InternalRoleController controller = new InternalRoleController(users, roles);

  @Test
  @DisplayName("revoking SELLER removes the role from the user")
  void revokesSeller() {
    UserAccount user = mock(UserAccount.class);
    RoleEntity seller = mock(RoleEntity.class);
    when(users.findWithRolesById(userId)).thenReturn(Optional.of(user));
    when(roles.findByName("ROLE_SELLER")).thenReturn(Optional.of(seller));

    controller.revoke(userId, "seller");

    verify(user).revoke(seller);
  }

  @Test
  @DisplayName("ADMIN cannot be revoked through the internal endpoint")
  void adminIsNotRevocable() {
    assertThatThrownBy(() -> controller.revoke(userId, "ADMIN"))
        .hasMessageContaining("cannot be revoked");
    verify(users, never()).findWithRolesById(userId);
  }
}
