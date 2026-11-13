package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Active refresh-token families as "sessions": list, revoke one, revoke all but the current. */
@Service
public class SessionService {

  public record SessionView(
      UUID familyId,
      String device,
      String ipAddress,
      String authMethod,
      Instant lastUsedAt,
      boolean current) {}

  private final RefreshTokenRepository tokens;
  private final TimeProvider time;

  SessionService(RefreshTokenRepository tokens, TimeProvider time) {
    this.tokens = tokens;
    this.time = time;
  }

  @Transactional(readOnly = true)
  public List<SessionView> list(UUID userId, String currentRawToken) {
    UUID current = currentFamily(currentRawToken);
    return tokens.findActiveForUser(userId, time.now()).stream()
        .map(
            t ->
                new SessionView(
                    t.getFamilyId(),
                    DeviceInfo.describe(t.getUserAgent()),
                    DeviceInfo.maskedIp(t.getIpAddress()),
                    t.getAuthMethodRaw(),
                    t.getIssuedAt(),
                    t.getFamilyId().equals(current)))
        .toList();
  }

  @Transactional
  public void revoke(UUID userId, UUID familyId) {
    if (!tokens.existsByFamilyIdAndUserId(familyId, userId)) {
      throw new ResourceNotFoundException("Session", familyId);
    }
    tokens.revokeFamily(familyId, RevokeReason.LOGOUT.name(), time.now());
  }

  @Transactional
  public int revokeOthers(UUID userId, String currentRawToken) {
    UUID current = currentFamily(currentRawToken);
    if (current == null) {
      return tokens.revokeAllForUser(userId, RevokeReason.LOGOUT.name(), time.now());
    }
    return tokens.revokeOthers(userId, current, RevokeReason.LOGOUT.name(), time.now());
  }

  private UUID currentFamily(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return tokens.findByTokenHash(RefreshTokenSecret.hash(raw)).map(RefreshToken::getFamilyId).orElse(null);
  }
}
