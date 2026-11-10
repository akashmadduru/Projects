package com.acme.ecommerce.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Email canonicalisation - the reason case-insensitive lookup works at all. */
class UserAccountTest {

  @Test
  @DisplayName("emails are trimmed and lower-cased on every write and every lookup")
  void normalisesEmail() {
    assertThat(UserAccount.normaliseEmail("  Ada@Example.COM ")).isEqualTo("ada@example.com");
    assertThat(UserAccount.normaliseEmail(null)).isNull();
  }

  @Test
  @DisplayName("a user is created ACTIVE and may authenticate until disabled")
  void lifecycle() {
    UserAccount user = UserAccount.create("Ada@Example.com", "Ada", false);

    assertThat(user.getEmail()).isEqualTo("ada@example.com");
    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.canAuthenticate()).isTrue();

    user.disable();
    assertThat(user.canAuthenticate()).isFalse();
  }

  @Test
  @DisplayName("hashCode is stable across persistence, so a user survives being put in a Set")
  void hashCodeIsStableBeforeAndAfterIdAssignment() {
    UserAccount user = UserAccount.create("a@b.com", "A", false);
    int before = user.hashCode();
    // An id-derived hash would change here and lose the entity in any HashSet it
    // had already been added to.
    assertThat(user.hashCode()).isEqualTo(before);
  }
}
