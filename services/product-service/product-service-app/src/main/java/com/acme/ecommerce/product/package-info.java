/**
 * product-service - the platform's catalog service (Phase 1E).
 *
 * <p>Layering, matching auth-service: {@code web} binds and delegates, {@code service} owns the
 * transaction boundary, {@code repository} talks to the database, {@code domain} holds the entity.
 * No business logic in a controller and none in a repository.
 *
 * <p>Phase 1E is deliberately the shallowest service in the platform: one table, two read
 * endpoints. It exists to prove that a request survives gateway routing, Eureka resolution and
 * independent token validation. Catalog behaviour lands in Phase 2.
 */
package com.acme.ecommerce.product;
