package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Hooks the local link/provision decision into Spring Security's OIDC login.
 *
 * <p>Runs after the ID token has been validated (signature, issuer, audience, nonce) and after the
 * userinfo call, so every claim it reads has already been proven to come from the provider. Doing
 * the account decision anywhere earlier would mean acting on unverified data.
 *
 * <p>DELIBERATELY THIN. All it does is convert types and translate exceptions; the actual branch
 * logic lives in {@link FederatedIdentityResolver}, which needs no Spring Security types and is
 * therefore testable without a live IdP. That split is what makes the account-takeover-critical
 * logic unit-testable at all.
 *
 * <p>An {@link AuthException} is re-thrown as {@link OAuth2AuthenticationException} because that is
 * the only failure type the OAuth2 filter chain understands - anything else escapes as a 500 and
 * takes the user to a stack trace instead of the login page. The specific auth code is preserved in
 * the {@link OAuth2Error} so the failure handler can forward it to the SPA.
 */
@Service
public class PlatformOidcUserService extends OidcUserService {

  private final FederatedLoginService federatedLoginService;

  public PlatformOidcUserService(FederatedLoginService federatedLoginService) {
    this.federatedLoginService = federatedLoginService;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest userRequest) {
    OidcUser oidcUser = super.loadUser(userRequest);
    String registrationId = userRequest.getClientRegistration().getRegistrationId();
    OidcIdentity identity = OidcIdentity.from(registrationId, oidcUser);

    try {
      AuthenticatedPrincipal principal = federatedLoginService.resolve(identity);
      return new PlatformOidcUser(oidcUser, principal);
    } catch (AuthException e) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(e.authErrorCode().code(), e.authErrorCode().title(), null),
          e.getMessage(),
          e);
    }
  }
}
