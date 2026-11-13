package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.api.AuthClient;
import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.api.dto.MfaVerifyRequest;
import com.acme.ecommerce.auth.api.dto.RecoveryCodesResponse;
import com.acme.ecommerce.auth.api.dto.SessionResponse;
import com.acme.ecommerce.auth.api.dto.TokenResponse;
import com.acme.ecommerce.auth.api.dto.TwoFactorCodeRequest;
import com.acme.ecommerce.auth.api.dto.TwoFactorDisableRequest;
import com.acme.ecommerce.auth.api.dto.TwoFactorSetupResponse;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.service.TwoFactorService;
import com.acme.ecommerce.auth.token.SessionService;
import com.acme.ecommerce.auth.token.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 10: TOTP two-factor auth (enrolment + login step) and session management. */
@RestController
@RequestMapping(AuthClient.BASE_PATH)
public class TwoFactorController {

  private final TwoFactorService twoFactor;
  private final SessionService sessions;
  private final RefreshCookieWriter refreshCookies;
  private final RequestDeviceContext deviceContexts;

  public TwoFactorController(
      TwoFactorService twoFactor,
      SessionService sessions,
      RefreshCookieWriter refreshCookies,
      RequestDeviceContext deviceContexts) {
    this.twoFactor = twoFactor;
    this.sessions = sessions;
    this.refreshCookies = refreshCookies;
    this.deviceContexts = deviceContexts;
  }

  @PostMapping("/2fa/setup")
  public TwoFactorSetupResponse setup(@AuthenticationPrincipal Jwt jwt) {
    TwoFactorService.Setup s = twoFactor.setup(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
    return new TwoFactorSetupResponse(s.otpauthUrl(), s.secretBase32());
  }

  @PostMapping("/2fa/enable")
  public RecoveryCodesResponse enable(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TwoFactorCodeRequest request) {
    return new RecoveryCodesResponse(
        twoFactor.enable(UUID.fromString(jwt.getSubject()), request.code()));
  }

  @PostMapping("/2fa/disable")
  public ResponseEntity<Void> disable(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TwoFactorDisableRequest request) {
    twoFactor.disable(
        UUID.fromString(jwt.getSubject()),
        request.code(),
        request.recoveryCode(),
        request.password());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/2fa/status")
  public Map<String, Object> status(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return Map.of(
        "enabled", twoFactor.isEnabled(userId),
        "recoveryCodesRemaining", twoFactor.recoveryCodesRemaining(userId));
  }

  /** PUBLIC (the caller has no session yet): authenticated by the single-use mfaToken. */
  @PostMapping("/2fa/verify")
  public ResponseEntity<TokenResponse> verify(
      @Valid @RequestBody MfaVerifyRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    boolean hasCode = request.code() != null && !request.code().isBlank();
    boolean hasRecovery = request.recoveryCode() != null && !request.recoveryCode().isBlank();
    if (hasCode == hasRecovery) {
      throw new AuthException(AuthErrorCode.AUTH_MFA_INVALID, "Provide exactly one of code / recoveryCode");
    }
    TokenPair pair =
        twoFactor.verifyLogin(
            request.mfaToken(), request.code(), request.recoveryCode(), deviceContexts.from(httpRequest));
    refreshCookies.write(httpResponse, pair);
    return ResponseEntity.ok(
        TokenResponse.bearer(
            pair.accessToken(),
            pair.expiresInSeconds(),
            AuthMethod.fromClaimValue(AuthController.claimOf(pair))));
  }

  @GetMapping("/sessions")
  public List<SessionResponse> listSessions(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
    return sessions.list(UUID.fromString(jwt.getSubject()), refreshCookies.read(httpRequest).orElse(null))
        .stream()
        .map(
            s ->
                new SessionResponse(
                    s.familyId(), s.device(), s.ipAddress(), s.authMethod(), s.lastUsedAt(), s.current()))
        .toList();
  }

  @DeleteMapping("/sessions/{familyId}")
  public ResponseEntity<Void> revokeSession(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
    sessions.revoke(UUID.fromString(jwt.getSubject()), familyId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/sessions/revoke-others")
  public Map<String, Integer> revokeOthers(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
    int n =
        sessions.revokeOthers(
            UUID.fromString(jwt.getSubject()), refreshCookies.read(httpRequest).orElse(null));
    return Map.of("revoked", n);
  }
}
