/**
 * Auth service published contract.
 *
 * <p>Holds the declarative {@link com.acme.ecommerce.auth.api.AuthClient} and the stable error-code
 * catalogue. Request/response payloads live in {@code com.acme.ecommerce.auth.api.dto}.
 *
 * <p>NOTHING in this module may reference an entity, a repository, or any auth-service internal
 * type. It is compiled into every consumer, so a leak here becomes a compile-time coupling between
 * two independently deployable services.
 */
package com.acme.ecommerce.auth.api;
