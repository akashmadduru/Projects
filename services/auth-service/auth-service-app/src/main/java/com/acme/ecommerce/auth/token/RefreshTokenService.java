package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.cache.UserSnapshotFactory;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The refresh-rotation state machine, including reuse detection.
 *
 * <p>This is the security-critical heart of the service. Every branch below exists because of a
 * specific failure mode, and collapsing any two of them reintroduces the mode:
 *
 * <pre>
 *   presented token
 *        |
 *        +-- hash not found ................. 401 AUTH_REFRESH_INVALID, NO side effects
 *        |                                    (an unknown hash cannot be attributed to any user,
 *        |                                     so there is nothing that could safely be revoked -
 *        |                                     and reacting would let an attacker with random
 *        |                                     guesses trigger revocations for strangers)
 *        |
 *        +-- found, already revoked
 *        |     |
 *        |     +-- rotated < 10s ago ........ 401 AUTH_REFRESH_RACE, family SURVIVES, cookie KEPT
 *        |     |                              (two tabs, or a retried request whose response was
 *        |     |                               lost - punishing this logs real users out constantly)
 *        |     |
 *        |     +-- rotated >= 10s ago ....... REUSE DETECTED: revoke the ENTIRE family,
 *        |     |                              WARN + alertable metric, 401, clear cookie
 *        |     |                              (the legitimate holder and the thief now both hold
 *        |     |                               tokens from the same chain; there is no way to tell
 *        |     |                               which is which, so the only safe move is to end the
 *        |     |                               session and make the real user log in again)
 *        |     |
 *        |     +-- revoked for another reason  401 AUTH_REFRESH_INVALID
 *        |                                    (logout / password change / already-revoked family:
 *        |                                     the session was deliberately ended, a stale cookie
 *        |                                     afterwards is normal and not an attack)
 *        |
 *        +-- found, expired ................. mark EXPIRED, 401 AUTH_REFRESH_INVALID
 *        |                                    (family survives - an expired token is not evidence
 *        |                                     of theft)
 *        |
 *        +-- found, valid
 *              |
 *              +-- compare-and-set lost ..... 401 AUTH_REFRESH_RACE, family SURVIVES
 *              +-- compare-and-set won ...... issue child via TokenService, 200
 * </pre>
 *
 * <p>THE COMPARE-AND-SET IS NOT OPTIONAL AND IS NOT A READ-THEN-WRITE. {@code UPDATE ... WHERE id=?
 * AND revoked_at IS NULL} with an affected-row check is what makes concurrent rotation safe. The
 * obvious alternative - read the row, check {@code revokedAt == null}, then write - has a window
 * between the check and the write in which a second request does the same thing, and both then mint
 * a child in the same family. The next request from either client looks exactly like token reuse,
 * so the mechanism designed to catch thieves starts revoking honest users' sessions instead.
 */
@Service
public class RefreshTokenService {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

  private final RefreshTokenStore store;
  private final TokenService tokenService;
  private final PrincipalLoader principalLoader;
  private final UserSnapshotFactory snapshots;
  private final TimeProvider timeProvider;
  private final AuthMetrics metrics;
  private final Duration graceWindow;

  RefreshTokenService(
      RefreshTokenStore store,
      TokenService tokenService,
      PrincipalLoader principalLoader,
      UserSnapshotFactory snapshots,
      TimeProvider timeProvider,
      AuthMetrics metrics,
      AuthProperties properties) {
    this.store = store;
    this.tokenService = tokenService;
    this.principalLoader = principalLoader;
    this.snapshots = snapshots;
    this.timeProvider = timeProvider;
    this.metrics = metrics;
    this.graceWindow = properties.getRefresh().rotationGraceWindow();
  }

  /**
   * Exchanges a refresh token for a fresh pair, rotating it.
   *
   * @param rawToken the raw value from the HttpOnly cookie
   * @param device recorded on the new token for forensics
   * @return the new pair; the caller writes the new refresh cookie
   * @throws AuthException on every rejection path, with the specific {@link AuthErrorCode}
   */
  public TokenPair rotate(String rawToken, DeviceContext device) {
    if (rawToken == null || rawToken.isBlank()) {
      metrics.refreshInvalid();
      throw new AuthException(AuthErrorCode.AUTH_REFRESH_INVALID, "No refresh token presented");
    }

    RefreshTokenSnapshot token =
        store
            .findByHash(RefreshTokenSecret.hash(rawToken))
            .orElseThrow(
                () -> {
                  metrics.refreshInvalid();
                  // No log of the token or its hash: both are bearer-equivalent.
                  return new AuthException(
                      AuthErrorCode.AUTH_REFRESH_INVALID, "Refresh token not recognised");
                });

    Instant now = timeProvider.now();

    if (!token.isActive()) {
      throw handleReplay(token, device, now);
    }

    if (token.isExpiredAt(now)) {
      store.markExpired(token.id());
      metrics.refreshInvalid();
      throw new AuthException(
          AuthErrorCode.AUTH_REFRESH_INVALID, "Refresh token expired for userId=" + token.userId());
    }

    if (!store.claimForRotation(token.id())) {
      // Someone else revoked or rotated this exact row between our read and our
      // write. The family is intact and the winner already holds a valid child,
      // so this is a race, not an attack.
      metrics.refreshRace();
      throw new AuthException(
          AuthErrorCode.AUTH_REFRESH_RACE, "Lost the rotation race for token " + token.id());
    }

    AuthenticatedPrincipal principal = principalLoader.load(token.userId(), token.authMethod());
    TokenPair pair =
        tokenService.issueTokenPair(
            principal, device, token.familyId(), token.id(), snapshots.snapshotFor(principal));
    metrics.refreshRotated();
    return pair;
  }

  /**
   * Decides what a replayed (already-revoked) token means.
   *
   * @return the exception to throw; returning rather than throwing keeps the caller's control flow
   *     obvious at the call site
   */
  private AuthException handleReplay(
      RefreshTokenSnapshot token, DeviceContext device, Instant now) {

    if (!token.wasRotated()) {
      // Logout, password change, administrative revocation, or an earlier family
      // revocation. The session was deliberately ended; a stale cookie turning up
      // afterwards is ordinary client behaviour.
      metrics.refreshInvalid();
      return new AuthException(
          AuthErrorCode.AUTH_REFRESH_INVALID,
          "Refresh token was revoked (" + token.revokedReason() + ")");
    }

    Instant rotatedAt = token.rotatedAt();
    boolean withinGrace = rotatedAt != null && rotatedAt.plus(graceWindow).isAfter(now);
    if (withinGrace) {
      // A legitimate client racing itself. The winner's child token is valid and
      // the client already holds it, so the family MUST survive and the cookie
      // MUST NOT be cleared - clearing it would log out a user who did nothing
      // wrong, on a code path that fires whenever two tabs load at once.
      metrics.refreshRace();
      return new AuthException(
          AuthErrorCode.AUTH_REFRESH_RACE,
          "Refresh token replayed " + Duration.between(rotatedAt, now) + " after rotation");
    }

    // Outside the window: a token that was spent long ago has come back. Either
    // the legitimate user's cookie was stolen and the thief is using it, or the
    // user's own stolen token is being used against them - and there is no way to
    // tell which party is which. Ending the whole family is the only action that
    // is safe under both readings.
    int revoked = tokenService.revokeFamily(token.familyId(), RevokeReason.REUSE_DETECTED);
    metrics.refreshReuseDetected();

    // WARN, with the forensic context and NOTHING bearer-equivalent. This is the
    // line an on-call engineer reads after the alert fires.
    log.warn(
        "REFRESH TOKEN REUSE DETECTED - revoked {} token(s) in family. "
            + "userId={} familyId={} tokenId={} rotatedAt={} presentedAt={} ip={} userAgent={}",
        revoked,
        token.userId(),
        token.familyId(),
        token.id(),
        rotatedAt,
        now,
        device == null ? null : device.ipAddress(),
        device == null ? null : device.userAgent());

    return new AuthException(
        AuthErrorCode.AUTH_REFRESH_REUSE_DETECTED,
        "Refresh token reuse detected for userId=" + token.userId());
  }

  /**
   * Logs a session out by revoking its entire family.
   *
   * <p>DELIBERATELY SILENT ABOUT FAILURE. An unknown or already-revoked token produces no exception
   * and no metric: the caller responds 204 either way. A logout endpoint that can fail is worse
   * than useless - the user's intent is "end this session", and an error response leaves them
   * looking at a screen that says logout failed while their cookie is still sitting in the browser.
   *
   * @return the number of tokens revoked; 0 is a perfectly normal outcome
   */
  public int logout(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return 0;
    }
    Optional<RefreshTokenSnapshot> token = store.findByHash(RefreshTokenSecret.hash(rawToken));
    if (token.isEmpty()) {
      return 0;
    }
    UUID familyId = token.get().familyId();
    int revoked = store.revokeFamily(familyId, RevokeReason.LOGOUT);
    log.debug("Logout revoked {} token(s) in family {}", revoked, familyId);
    return revoked;
  }

  /**
   * Revokes every session a user has. Called after a password change, and available to
   * administrative tooling.
   */
  public int revokeAllSessions(UUID userId, RevokeReason reason) {
    return tokenService.revokeAllSessions(userId, reason);
  }
}
