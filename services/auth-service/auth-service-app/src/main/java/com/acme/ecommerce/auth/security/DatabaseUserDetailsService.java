package com.acme.ecommerce.auth.security;

import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.util.Optional;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a user for password authentication.
 *
 * <p>THROWS {@link UsernameNotFoundException} FOR A USER WITH NO PASSWORD ROW, not just for a user
 * who does not exist. A Google-provisioned account has no {@code user_credentials} row at all, and
 * the two cases must be indistinguishable from outside - otherwise "wrong password" versus "this
 * address exists but signed up with Google" becomes a free oracle for an attacker mapping the
 * organisation.
 *
 * <p>The timing side of that indistinguishability is handled for us: {@code
 * DaoAuthenticationProvider} runs a dummy password comparison whenever this method throws, so a
 * request for a non-existent user costs the same ~100ms of Argon2 work as a request for a real one.
 * That mitigation only fires if {@code hideUserNotFoundExceptions} is left on (it is, by default) -
 * turning it off to "get better error messages" reopens the enumeration hole in two different ways
 * at once.
 *
 * <p>Fetches roles in the SAME query as the user, so a login is two round trips (user+roles,
 * credential) rather than four.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

  private final UserAccountRepository users;
  private final UserCredentialRepository credentials;
  private final TimeProvider timeProvider;

  public DatabaseUserDetailsService(
      UserAccountRepository users,
      UserCredentialRepository credentials,
      TimeProvider timeProvider) {
    this.users = users;
    this.credentials = credentials;
    this.timeProvider = timeProvider;
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String username) {
    String email = UserAccount.normaliseEmail(username);

    Optional<UserAccount> user = users.findWithRolesByEmail(email);
    if (user.isEmpty()) {
      throw new UsernameNotFoundException("No user for the supplied identifier");
    }

    Optional<UserCredential> credential = credentials.findById(user.get().getId());
    if (credential.isEmpty()) {
      // Social-only account. Same exception, same message, same timing.
      throw new UsernameNotFoundException("No user for the supplied identifier");
    }

    return AuthUserDetails.of(user.get(), credential.get(), timeProvider.now());
  }
}
