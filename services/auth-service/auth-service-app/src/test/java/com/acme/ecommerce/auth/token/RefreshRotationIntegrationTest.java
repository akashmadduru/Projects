package com.acme.ecommerce.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.common.security.Roles;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The refresh-rotation state machine, end to end against real Postgres.
 *
 * <p>DELIBERATELY IN THE {@code token} PACKAGE. {@code RefreshTokenRepository} is package-private -
 * that is the architectural rule these tests exist to protect - so the only way to assert on the
 * stored rows is from inside the package. A test that had to widen production visibility to observe
 * something would have already broken the thing it was testing.
 *
 * <p>Every branch of {@code RefreshTokenService#rotate} has a test here, because each branch exists
 * to prevent a specific, real failure and collapsing any two of them silently reintroduces it.
 */
class RefreshRotationIntegrationTest extends AbstractAuthIntegrationTest {

  @Autowired private TokenService tokenService;
  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private UserFixtures userFixtures;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UserAccount user;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    user =
        userFixtures.createPasswordUser(
            "rotate@example.com", "correct-horse-battery", Roles.ROLE_VIEWER);
  }

  private AuthenticatedPrincipal principal() {
    return new AuthenticatedPrincipal(
        user.getId(), user.getEmail(), Set.of("VIEWER"), AuthMethod.PASSWORD);
  }

  private static DeviceContext device() {
    return new DeviceContext("JUnit/1.0", "203.0.113.7");
  }

  // --- the happy path --------------------------------------------------------

  @Test
  @DisplayName("a valid refresh token rotates into a child in the same family")
  void rotatesIntoChild() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    RefreshToken root =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(first.refreshTokenRaw()))
            .orElseThrow();

    TokenPair second = refreshTokenService.rotate(first.refreshTokenRaw(), device());

    RefreshToken child =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(second.refreshTokenRaw()))
            .orElseThrow();

    assertThat(second.refreshTokenRaw()).isNotEqualTo(first.refreshTokenRaw());
    assertThat(child.getFamilyId()).isEqualTo(root.getFamilyId());
    assertThat(child.getParentId()).isEqualTo(root.getId());
    // The session's original authentication method survives rotation.
    assertThat(child.getAuthMethod()).isEqualTo(AuthMethod.PASSWORD);

    RefreshToken rotatedRoot = refreshTokens.findById(root.getId()).orElseThrow();
    assertThat(rotatedRoot.getRevokedAt()).isNotNull();
    assertThat(rotatedRoot.getRotatedAt()).isNotNull();
    assertThat(rotatedRoot.getRevokedReason()).isEqualTo(RevokeReason.ROTATED.name());
  }

  @Test
  @DisplayName("the family's absolute expiry is copied verbatim, NOT recomputed, on rotation")
  void absoluteExpiryIsCopiedNotRecomputed() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    RefreshToken root =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(first.refreshTokenRaw()))
            .orElseThrow();

    TokenPair second = refreshTokenService.rotate(first.refreshTokenRaw(), device());
    TokenPair third = refreshTokenService.rotate(second.refreshTokenRaw(), device());

    RefreshToken grandchild =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(third.refreshTokenRaw()))
            .orElseThrow();

    // If this ever recomputes as `now + 30d`, the 30-day cap becomes an
    // unbounded session and nothing else in the system would notice.
    assertThat(grandchild.getFamilyAbsoluteExpiresAt())
        .isEqualTo(root.getFamilyAbsoluteExpiresAt());
  }

  @Test
  @DisplayName("the sliding window moves forward on each rotation")
  void slidingExpiryMovesForward() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    RefreshToken root =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(first.refreshTokenRaw()))
            .orElseThrow();

    // Age the root so the child's fresh +14d is measurably later.
    jdbcTemplate.update(
        "UPDATE auth.refresh_tokens SET expires_at = expires_at - interval '1 day' WHERE id = ?",
        root.getId());

    TokenPair second = refreshTokenService.rotate(first.refreshTokenRaw(), device());
    RefreshToken child =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(second.refreshTokenRaw()))
            .orElseThrow();

    assertThat(child.getExpiresAt()).isAfter(root.getExpiresAt().minus(1, ChronoUnit.DAYS));
  }

  // --- rejection paths -------------------------------------------------------

  @Test
  @DisplayName("an unknown token hash is rejected with NO side effects")
  void unknownTokenHasNoSideEffects() {
    TokenPair existing = tokenService.issueTokenPair(principal(), device(), null, null, null);
    long before = refreshTokens.count();

    assertThatThrownBy(() -> refreshTokenService.rotate("not-a-real-token", device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);

    // Nothing created, nothing revoked. An unknown hash cannot be attributed to
    // anyone, so reacting to it would let random guesses revoke strangers'
    // sessions.
    assertThat(refreshTokens.count()).isEqualTo(before);
    RefreshToken untouched =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(existing.refreshTokenRaw()))
            .orElseThrow();
    assertThat(untouched.getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("an expired token is marked EXPIRED and the family survives")
  void expiredTokenDoesNotRevokeFamily() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    UUID rootId =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(first.refreshTokenRaw()))
            .orElseThrow()
            .getId();

    jdbcTemplate.update(
        "UPDATE auth.refresh_tokens SET expires_at = now() - interval '1 minute' WHERE id = ?",
        rootId);

    assertThatThrownBy(() -> refreshTokenService.rotate(first.refreshTokenRaw(), device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);

    RefreshToken expired = refreshTokens.findById(rootId).orElseThrow();
    assertThat(expired.getRevokedReason()).isEqualTo(RevokeReason.EXPIRED.name());
  }

  @Test
  @DisplayName("the 30-day family cap ends the session even while the sliding window is open")
  void absoluteFamilyCapIsEnforced() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    UUID rootId =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(first.refreshTokenRaw()))
            .orElseThrow()
            .getId();

    // expires_at is still comfortably in the future; only the family cap has
    // passed. This is the case a naive `expires_at`-only check would miss.
    jdbcTemplate.update(
        "UPDATE auth.refresh_tokens SET family_absolute_expires_at = now() - interval '1 minute' WHERE id = ?",
        rootId);

    assertThatThrownBy(() -> refreshTokenService.rotate(first.refreshTokenRaw(), device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);
  }

  // --- reuse detection -------------------------------------------------------

  @Test
  @DisplayName("replay INSIDE the grace window is a race: 401, family NOT revoked")
  void replayInsideGraceWindowPreservesFamily() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    TokenPair second = refreshTokenService.rotate(first.refreshTokenRaw(), device());
    UUID familyId =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(second.refreshTokenRaw()))
            .orElseThrow()
            .getFamilyId();

    // rotated_at is a fraction of a second ago, well inside the 10s window.
    assertThatThrownBy(() -> refreshTokenService.rotate(first.refreshTokenRaw(), device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_RACE);

    // The winner's child is still perfectly usable - which is the entire point:
    // two tabs refreshing at once must not log the user out.
    assertThat(refreshTokens.countByFamilyIdAndRevokedAtIsNull(familyId)).isEqualTo(1);
    assertThat(refreshTokenService.rotate(second.refreshTokenRaw(), device())).isNotNull();
  }

  @Test
  @DisplayName("replay OUTSIDE the grace window revokes EVERY token in the family")
  void replayOutsideGraceWindowRevokesWholeFamily() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    TokenPair second = refreshTokenService.rotate(first.refreshTokenRaw(), device());
    TokenPair third = refreshTokenService.rotate(second.refreshTokenRaw(), device());

    UUID familyId =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(third.refreshTokenRaw()))
            .orElseThrow()
            .getFamilyId();
    assertThat(refreshTokens.countByFamilyIdAndRevokedAtIsNull(familyId)).isEqualTo(1);

    // Push the first token's rotation far into the past so the replay lands
    // outside the grace window and is unambiguously a replay, not a race.
    jdbcTemplate.update(
        "UPDATE auth.refresh_tokens SET rotated_at = now() - interval '1 hour' WHERE token_hash = ?",
        RefreshTokenSecret.hash(first.refreshTokenRaw()));

    assertThatThrownBy(() -> refreshTokenService.rotate(first.refreshTokenRaw(), device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_REUSE_DETECTED);

    // EVERY token in the family, including the currently-valid newest one, is now
    // dead. The legitimate user is logged out too - that is the intended cost,
    // because there is no way to tell which holder is the thief.
    assertThat(refreshTokens.countByFamilyIdAndRevokedAtIsNull(familyId)).isZero();
    assertThat(refreshTokens.countByFamilyId(familyId)).isEqualTo(3);

    List<String> reasons =
        jdbcTemplate.queryForList(
            "SELECT revoked_reason FROM auth.refresh_tokens WHERE family_id = ?",
            String.class,
            familyId);
    assertThat(reasons).contains(RevokeReason.REUSE_DETECTED.name());

    // And the newest token really is unusable afterwards.
    assertThatThrownBy(() -> refreshTokenService.rotate(third.refreshTokenRaw(), device()))
        .isInstanceOf(AuthException.class);
  }

  @Test
  @DisplayName("a token revoked by LOGOUT is invalid, but is not reported as reuse")
  void logoutRevokedTokenIsInvalidNotReuse() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    refreshTokenService.logout(first.refreshTokenRaw());

    // A stale cookie after logout is ordinary client behaviour, not evidence of
    // theft. Reporting it as reuse would fire the security alert on every user
    // who closes a second tab.
    assertThatThrownBy(() -> refreshTokenService.rotate(first.refreshTokenRaw(), device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);
  }

  @Test
  @DisplayName("logout revokes the whole family and is idempotent")
  void logoutRevokesFamilyAndIsIdempotent() {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    TokenPair second = refreshTokenService.rotate(first.refreshTokenRaw(), device());
    UUID familyId =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(second.refreshTokenRaw()))
            .orElseThrow()
            .getFamilyId();

    assertThat(refreshTokenService.logout(second.refreshTokenRaw())).isEqualTo(1);
    assertThat(refreshTokens.countByFamilyIdAndRevokedAtIsNull(familyId)).isZero();

    // Second call, and a call with nonsense, both no-op rather than throwing.
    assertThat(refreshTokenService.logout(second.refreshTokenRaw())).isZero();
    assertThat(refreshTokenService.logout("garbage")).isZero();
    assertThat(refreshTokenService.logout(null)).isZero();
  }

  // --- concurrency -----------------------------------------------------------

  @Test
  @DisplayName("two threads racing the same token: exactly one wins the compare-and-set")
  void concurrentRotationHasExactlyOneWinner() throws Exception {
    TokenPair first = tokenService.issueTokenPair(principal(), device(), null, null, null);
    UUID familyId =
        refreshTokens
            .findByTokenHash(RefreshTokenSecret.hash(first.refreshTokenRaw()))
            .orElseThrow()
            .getFamilyId();

    // A barrier, not a sleep: both threads issue their UPDATE as close to
    // simultaneously as the JVM allows, which is what actually exercises the
    // row-lock/re-evaluation path in Postgres.
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Object> attempt =
        () -> {
          barrier.await();
          try {
            return refreshTokenService.rotate(first.refreshTokenRaw(), device());
          } catch (AuthException e) {
            return e;
          }
        };

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Object> a = pool.submit(attempt);
      Future<Object> b = pool.submit(attempt);
      Object resultA = a.get();
      Object resultB = b.get();

      long winners = List.of(resultA, resultB).stream().filter(TokenPair.class::isInstance).count();
      long losers =
          List.of(resultA, resultB).stream()
              .filter(AuthException.class::isInstance)
              .map(AuthException.class::cast)
              .filter(e -> e.authErrorCode() == AuthErrorCode.AUTH_REFRESH_RACE)
              .count();

      // If this ever produced two winners, both children would live in the same
      // family and the next request from either client would look like reuse -
      // the mechanism built to catch thieves would start revoking honest users.
      assertThat(winners).isEqualTo(1);
      assertThat(losers).isEqualTo(1);
    }

    // Exactly one live token in the family: the single child.
    assertThat(refreshTokens.countByFamilyIdAndRevokedAtIsNull(familyId)).isEqualTo(1);
    assertThat(refreshTokens.countByFamilyId(familyId)).isEqualTo(2);
  }

  @Test
  @DisplayName("revoking all sessions kills every family the user owns")
  void revokeAllSessions() {
    TokenPair sessionOne = tokenService.issueTokenPair(principal(), device(), null, null, null);
    TokenPair sessionTwo = tokenService.issueTokenPair(principal(), device(), null, null, null);

    assertThat(refreshTokenService.revokeAllSessions(user.getId(), RevokeReason.PASSWORD_CHANGE))
        .isEqualTo(2);

    for (TokenPair pair : List.of(sessionOne, sessionTwo)) {
      assertThatThrownBy(() -> refreshTokenService.rotate(pair.refreshTokenRaw(), device()))
          .isInstanceOf(AuthException.class);
    }
  }

  @Test
  @DisplayName("the raw refresh token is never stored - only its SHA-256 hash")
  void rawTokenIsNeverPersisted() {
    TokenPair pair = tokenService.issueTokenPair(principal(), device(), null, null, null);

    Integer matches =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM auth.refresh_tokens WHERE token_hash = ?",
            Integer.class,
            pair.refreshTokenRaw());
    assertThat(matches).isZero();

    Instant issuedAt =
        jdbcTemplate.queryForObject(
            "SELECT issued_at FROM auth.refresh_tokens WHERE token_hash = ?",
            Instant.class,
            RefreshTokenSecret.hash(pair.refreshTokenRaw()));
    assertThat(issuedAt).isNotNull();
  }
}
