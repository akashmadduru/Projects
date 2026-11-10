package com.acme.ecommerce.auth.support;

import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates users for integration tests.
 *
 * <p>Goes through the real entities and repositories rather than raw SQL, so a test fixture cannot
 * quietly construct a row that production code could never produce (for example a credential whose
 * hash was not made by the configured encoder).
 */
@Component
public class UserFixtures {

  private final UserAccountRepository users;
  private final UserCredentialRepository credentials;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;

  public UserFixtures(
      UserAccountRepository users,
      UserCredentialRepository credentials,
      RoleRepository roles,
      PasswordEncoder passwordEncoder) {
    this.users = users;
    this.credentials = credentials;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
  }

  /** A password user with the given role. */
  @Transactional
  public UserAccount createPasswordUser(String email, String rawPassword, String roleName) {
    RoleEntity role = roles.findByName(roleName).orElseThrow();
    UserAccount user = UserAccount.create(email, "Test " + email, true);
    user.grant(role);
    users.saveAndFlush(user);
    credentials.saveAndFlush(
        UserCredential.create(user.getId(), passwordEncoder.encode(rawPassword), Instant.now()));
    return user;
  }

  /** Administratively disables a user, so authentication must be refused. */
  @Transactional
  public void disable(java.util.UUID userId) {
    UserAccount user = users.findById(userId).orElseThrow();
    user.disable();
    users.saveAndFlush(user);
  }

  /** A social-only user: no user_credentials row at all. */
  @Transactional
  public UserAccount createSocialOnlyUser(String email, String roleName) {
    RoleEntity role = roles.findByName(roleName).orElseThrow();
    UserAccount user = UserAccount.create(email, "Test " + email, true);
    user.grant(role);
    return users.saveAndFlush(user);
  }
}
