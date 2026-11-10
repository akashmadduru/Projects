package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.FederatedIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for external-IdP links.
 *
 * <p>THE ONLY AUTHENTICATION LOOKUP IS {@link #findByProviderAndProviderUserId}, keyed on the OIDC
 * {@code sub}. There is deliberately no {@code findByProviderEmail}: an email-keyed lookup is how
 * account-takeover-by-address-reassignment gets built by accident.
 */
public interface FederatedIdentityRepository extends JpaRepository<FederatedIdentity, UUID> {

  /** Backed by the UNIQUE index on {@code (provider, provider_user_id)}. */
  Optional<FederatedIdentity> findByProviderAndProviderUserId(
      String provider, String providerUserId);

  /**
   * Whether this user has already linked this provider. Backed by UNIQUE {@code (user_id,
   * provider)}.
   */
  Optional<FederatedIdentity> findByUserIdAndProvider(UUID userId, String provider);
}
