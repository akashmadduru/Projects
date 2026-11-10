package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.cache.UserSessionCache;
import com.acme.ecommerce.auth.cache.UserSnapshot;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE CONVERGENCE POINT. The one and only place in this service where a token pair is minted.
 *
 * <p>Password login, Google login and refresh rotation are three front doors into this single
 * method. No controller, no OAuth2 success handler and no future authentication mechanism may build
 * a JWT or insert a refresh token itself. That rule is what makes statements like "every token has
 * a jti", "every session is capped at 30 days" and "every issuance is counted" true by construction
 * rather than by convention - and it is why a second issuance path would be a security regression
 * even if its code were identical today.
 *
 * <p>{@link AccessTokenIssuer} and {@link RefreshTokenRepository} are both package-private, so this
 * is also enforced by the compiler and not just by this comment.
 */
@Service
public class TokenService {

  private static final Logger log = LoggerFactory.getLogger(TokenService.class);

  private final RefreshTokenRepository refreshTokens;
  private final AccessTokenIssuer accessTokenIssuer;
  private final TimeProvider timeProvider;
  private final AuthProperties.Refresh refreshConfig;
  private final AuthMetrics metrics;
  private final UserSessionCache sessionCache;

  TokenService(
      RefreshTokenRepository refreshTokens,
      AccessTokenIssuer accessTokenIssuer,
      TimeProvider timeProvider,
      AuthProperties properties,
      AuthMetrics metrics,
      UserSessionCache sessionCache) {
    this.refreshTokens = refreshTokens;
    this.accessTokenIssuer = accessTokenIssuer;
    this.timeProvider = timeProvider;
    this.refreshConfig = properties.getRefresh();
    this.metrics = metrics;
    this.sessionCache = sessionCache;
  }

  /**
   * Issues an access token plus a refresh token, either starting a new family or continuing an
   * existing one.
   *
   * <p>The transaction covers exactly one unit of work: persisting the new refresh token. The
   * access token is signed inside it only because it must share the same {@code issuedAt}; signing
   * is CPU-only and adds no lock time.
   *
   * @param principal who the tokens are for; identical in shape whether they arrived via password
   *     or via Google, which is what makes the two paths produce interchangeable tokens
   * @param device user-agent / IP, recorded for forensics only
   * @param familyId {@code null} to START A NEW FAMILY (a fresh login). Non-null to CONTINUE an
   *     existing family (a rotation), in which case the new token inherits that family's absolute
   *     expiry unchanged.
   * @param parentId the token being rotated; required when {@code familyId} is non-null, and {@code
   *     null} for a fresh login
   * @param snapshot the caller's already-loaded {@link UserAccount} view, cached to Redis after
   *     issuance (see {@link UserSessionCache}); {@code null} is tolerated so that a failure to
   *     build the snapshot never blocks login/refresh - see the callers of this method
   */
  @Transactional
  public TokenPair issueTokenPair(
      AuthenticatedPrincipal principal,
      DeviceContext device,
      UUID familyId,
      UUID parentId,
      UserSnapshot snapshot) {

    Objects.requireNonNull(principal, "principal");
    Instant now = timeProvider.now();

    String rawRefreshToken = RefreshTokenSecret.generate();
    String tokenHash = RefreshTokenSecret.hash(rawRefreshToken);
    Instant slidingExpiry = now.plus(refreshConfig.ttl());

    RefreshToken refreshToken =
        familyId == null
            ? startFamily(principal, device, now, tokenHash, slidingExpiry)
            : continueFamily(principal, device, now, tokenHash, slidingExpiry, familyId, parentId);

    refreshTokens.save(refreshToken);

    String accessToken = accessTokenIssuer.issue(principal, now);
    metrics.tokenIssued(principal.authMethod());

    // NEW - best-effort cache write. Does NOT need the after-commit discipline
    // ProductEventPublisher uses for Kafka: a cache write racing a rollback is
    // at worst a stale/orphaned entry that expires via TTL, not a correctness
    // bug (see docs/LLD-event-driven-inventory.md S2.3). UserSessionCache#put
    // itself never throws, and snapshot may be null (see the @param note
    // above), so this can never turn a successful issuance into a failure.
    if (snapshot != null) {
      sessionCache.put(principal.userId(), snapshot);
    }

    // Deliberately logs identifiers only. Never the access token, never the raw
    // refresh token, never the hash (which is a bearer-equivalent lookup key).
    log.debug(
        "Issued token pair userId={} familyId={} method={}",
        principal.userId(),
        refreshToken.getFamilyId(),
        principal.authMethod().claimValue());

    return new TokenPair(
        accessToken, accessTokenIssuer.ttlSeconds(), rawRefreshToken, refreshToken.getExpiresAt());
  }

  private RefreshToken startFamily(
      AuthenticatedPrincipal principal,
      DeviceContext device,
      Instant now,
      String tokenHash,
      Instant slidingExpiry) {

    // The absolute cap is fixed HERE, once, and never recomputed again for the
    // life of the chain.
    Instant absoluteExpiry = now.plus(refreshConfig.familyMaxLifetime());
    return RefreshToken.newFamilyRoot(
        principal.userId(),
        tokenHash,
        principal.authMethod(),
        now,
        earlier(slidingExpiry, absoluteExpiry),
        absoluteExpiry,
        device);
  }

  private RefreshToken continueFamily(
      AuthenticatedPrincipal principal,
      DeviceContext device,
      Instant now,
      String tokenHash,
      Instant slidingExpiry,
      UUID familyId,
      UUID parentId) {

    Objects.requireNonNull(parentId, "parentId is required when continuing an existing family");

    RefreshToken parent =
        refreshTokens
            .findById(parentId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parent refresh token " + parentId + " no longer exists"));

    if (!parent.getFamilyId().equals(familyId)) {
      // Only reachable through a programming error, but the consequence would be
      // a token that escapes its family's revocation, so it fails loudly.
      throw new IllegalStateException(
          "Parent token " + parentId + " does not belong to family " + familyId);
    }

    // THE COPY THAT MATTERS. The parent's absolute expiry is carried over
    // VERBATIM. Recomputing it as `now + familyMaxLifetime` here would push the
    // cap forward on every rotation, converting a 30-day maximum session into an
    // unbounded one - and nothing would fail, no test would go red, and the
    // control would simply not exist any more.
    Instant absoluteExpiry = parent.getFamilyAbsoluteExpiresAt();

    return RefreshToken.child(
        principal.userId(),
        tokenHash,
        familyId,
        parentId,
        principal.authMethod(),
        now,
        // The sliding window can never outlive the family cap.
        earlier(slidingExpiry, absoluteExpiry),
        absoluteExpiry,
        device);
  }

  /**
   * Revokes every live token in a family.
   *
   * <p>Lives here rather than in {@link RefreshTokenService} because it is the counterpart of
   * issuance and needs the same package-private repository. {@code REQUIRES_NEW} so the revocation
   * COMMITS even when the caller goes on to throw - reuse detection revokes the family and then
   * returns 401, and losing the revocation to a rollback would defeat the entire mechanism.
   *
   * @return number of tokens revoked
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int revokeFamily(UUID familyId, RevokeReason reason) {
    return refreshTokens.revokeFamily(familyId, reason.name(), timeProvider.now());
  }

  /** Revokes every live session belonging to a user (password change, administrative action). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int revokeAllSessions(UUID userId, RevokeReason reason) {
    int revoked = refreshTokens.revokeAllForUser(userId, reason.name(), timeProvider.now());
    if (revoked > 0) {
      log.info("Revoked {} session(s) for userId={} reason={}", revoked, userId, reason);
    }
    return revoked;
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
