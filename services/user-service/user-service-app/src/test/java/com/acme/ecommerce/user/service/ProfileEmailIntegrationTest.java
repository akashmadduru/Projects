package com.acme.ecommerce.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TD-BE-14: the profile email follows the token's email claim. */
class ProfileEmailIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private UserService users;

  @Test
  @DisplayName("a changed email claim refreshes the stored email; a missing claim keeps it")
  void emailFollowsTheClaim() {
    UUID user = UUID.randomUUID();

    assertThat(users.getProfile(user, "old@example.com").email()).isEqualTo("old@example.com");
    assertThat(users.getProfile(user, "new@example.com").email()).isEqualTo("new@example.com");
    assertThat(users.getProfile(user, null).email()).isEqualTo("new@example.com");
  }
}
