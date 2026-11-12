package com.acme.ecommerce.user.web;

import com.acme.ecommerce.user.membership.MembershipService;
import com.acme.ecommerce.user.membership.MembershipService.JoinResult;
import com.acme.ecommerce.user.membership.MembershipService.MembershipView;
import com.acme.ecommerce.user.membership.PreferenceService;
import com.acme.ecommerce.user.membership.PreferenceService.Preferences;
import com.acme.ecommerce.user.membership.PreferenceService.PreferencesUpdate;
import com.acme.ecommerce.user.membership.ReferralService;
import com.acme.ecommerce.user.membership.ReferralService.ReferralView;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase 8 customer endpoints: membership, notification preferences, referrals. */
@RestController
public class EngageController {

  public record JoinRequest(@NotBlank String plan, String currency) {}

  public record ClaimRequest(@NotBlank String code) {}

  private final MembershipService membership;
  private final PreferenceService preferences;
  private final ReferralService referrals;
  private final JdbcClient jdbc;

  public EngageController(
      MembershipService membership,
      PreferenceService preferences,
      ReferralService referrals,
      JdbcClient jdbc) {
    this.jdbc = jdbc;
    this.membership = membership;
    this.preferences = preferences;
    this.referrals = referrals;
  }

  @GetMapping("/api/membership")
  public MembershipView membership(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String currency) {
    return membership.view(uid(jwt), currency);
  }

  @PostMapping("/api/membership")
  @ResponseStatus(HttpStatus.CREATED)
  public JoinResult join(@AuthenticationPrincipal Jwt jwt, @RequestBody JoinRequest request) {
    return membership.join(uid(jwt), jwt.getClaimAsString("email"), request.plan(), request.currency());
  }

  @PostMapping("/api/membership/cancel")
  public MembershipView cancel(@AuthenticationPrincipal Jwt jwt) {
    return membership.cancel(uid(jwt));
  }

  @GetMapping("/api/users/me/preferences")
  public Preferences preferences(@AuthenticationPrincipal Jwt jwt) {
    return preferences.get(uid(jwt));
  }

  @PutMapping("/api/users/me/preferences")
  public Preferences updatePreferences(
      @AuthenticationPrincipal Jwt jwt, @RequestBody PreferencesUpdate request) {
    return preferences.update(uid(jwt), request);
  }

  @GetMapping("/api/referrals")
  public ReferralView referrals(@AuthenticationPrincipal Jwt jwt) {
    return referrals.view(uid(jwt));
  }

  @PostMapping("/api/referrals/claim")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void claim(@AuthenticationPrincipal Jwt jwt, @RequestBody ClaimRequest request) {
    referrals.claim(uid(jwt), request.code());
  }

  /** Makes sure a profile row (email) exists so the user is reachable by audience listings. */
  private UUID uid(Jwt jwt) {
    UUID id = UUID.fromString(jwt.getSubject());
    jdbc.sql(
            "INSERT INTO users.user_profiles (user_id, email) VALUES (:u, :e) ON CONFLICT (user_id)"
                + " DO UPDATE SET email = COALESCE(users.user_profiles.email, EXCLUDED.email)")
        .param("u", id)
        .param("e", jwt.getClaimAsString("email"))
        .update();
    return id;
  }
}
