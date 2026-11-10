package com.acme.ecommerce.auth.oauth2;

import java.util.Objects;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The handful of claims the federated-login decision actually depends on, lifted out of the
 * provider's {@code OidcUser}.
 *
 * <p>Exists so the branch logic in {@link FederatedIdentityResolver} can be unit-tested with a
 * three-line record literal instead of a mocked OIDC user, a mocked client registration and a live
 * connection to Google.
 *
 * @param provider registration id, e.g. {@code google}
 * @param subject the OIDC {@code sub}. THE identity key. Immutable and never reassigned by the IdP,
 *     unlike the email address.
 * @param email the {@code email} claim, normalised. Used ONLY to find an existing local account to
 *     link to, never to identify a returning federated user.
 * @param emailVerified the IdP's {@code email_verified} assertion. The single most important
 *     boolean in this flow: linking on an unverified address lets anyone who can register that
 *     address at the IdP inherit the local account.
 * @param displayName best available human-readable name
 */
record OidcIdentity(
    String provider, String subject, String email, boolean emailVerified, String displayName) {

  OidcIdentity {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(subject, "subject");
  }

  /** Extracts the identity from a provider-supplied user. */
  static OidcIdentity from(String registrationId, OidcUser oidcUser) {
    String email = com.acme.ecommerce.auth.domain.UserAccount.normaliseEmail(oidcUser.getEmail());
    Boolean verified = oidcUser.getEmailVerified();
    return new OidcIdentity(
        registrationId,
        oidcUser.getSubject(),
        email,
        Boolean.TRUE.equals(verified),
        displayNameOf(oidcUser, email));
  }

  private static String displayNameOf(OidcUser oidcUser, String email) {
    String fullName = oidcUser.getFullName();
    if (fullName != null && !fullName.isBlank()) {
      return fullName.length() > 200 ? fullName.substring(0, 200) : fullName;
    }
    if (email != null) {
      int at = email.indexOf('@');
      return at > 0 ? email.substring(0, at) : email;
    }
    return oidcUser.getSubject();
  }
}
