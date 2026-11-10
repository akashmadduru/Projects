package com.acme.ecommerce.auth.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing.
 *
 * <p>ARGON2id WITH OWASP'S MINIMUM PARAMETERS (m=19456 KiB, t=2, p=1). Argon2id is memory-hard: an
 * attacker with a GPU or an ASIC gains far less against it than against bcrypt or PBKDF2, because
 * the bottleneck is 19 MiB of memory per hash rather than arithmetic. That memory cost is also the
 * capacity consideration - see the sizing note below.
 *
 * <p>WRAPPED IN A {@link DelegatingPasswordEncoder}, which prefixes each stored hash with its
 * algorithm id ({@code {argon2id}$argon2id$v=19$...}). That prefix is what makes future migration
 * possible at all: when the parameters are raised (or Argon2 is superseded), old hashes keep
 * verifying with the encoder that produced them, and each user is transparently re-hashed on their
 * next successful login. Storing a bare hash with no algorithm marker means the only migration path
 * is a forced password reset for the entire user base.
 *
 * <p>BCrypt is registered as a legacy verifier only. Nothing in this service produces a bcrypt
 * hash; it is there so that an imported user table can be migrated without a reset.
 *
 * <p>CAPACITY NOTE. Each verification holds ~19 MiB and one core for ~50-100ms. Virtual threads do
 * NOT help here - the work is CPU-bound, not I/O-bound, so concurrency is bounded by cores, and
 * peak memory is roughly {@code 19 MiB x concurrent logins}. The per-IP throttle and the
 * per-account lockout both exist partly to keep that number small; a login flood is a
 * memory-exhaustion vector as much as a credential-stuffing one.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

  /** Id recorded in the hash prefix and mirrored into {@code user_credentials.algorithm}. */
  private static final String ARGON2ID = "argon2id";

  // OWASP Password Storage Cheat Sheet, Argon2id minimum configuration.
  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19456;
  private static final int ITERATIONS = 2;

  /**
   * The platform password encoder.
   *
   * <p>Requires BouncyCastle on the classpath - Spring's {@code Argon2PasswordEncoder} is a thin
   * adapter over BC's implementation and fails at runtime, not at startup, without it.
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    Argon2PasswordEncoder argon2 =
        new Argon2PasswordEncoder(
            SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

    return new DelegatingPasswordEncoder(
        ARGON2ID,
        Map.of(
            ARGON2ID,
            argon2,
            // Verification-only, for hashes that predate this service.
            "bcrypt",
            new BCryptPasswordEncoder()));
  }
}
