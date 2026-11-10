package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.UserCredential;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the local password proof.
 *
 * <p>Keyed by the user id, which is also the primary key of {@code users} - so {@code findById} is
 * the credential lookup and no separate index is needed.
 *
 * <p>There is deliberately no {@code findByPasswordHash}-style method and never should be.
 */
public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {}
