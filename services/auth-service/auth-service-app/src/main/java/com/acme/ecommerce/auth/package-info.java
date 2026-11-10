/**
 * auth-service: the platform's identity provider.
 *
 * <p>Issues the RS256 access tokens every other service trusts, and owns the rotating refresh-token
 * chain that keeps sessions alive. Two authentication front doors - local password and Google OIDC
 * - converge on ONE token-issuance method ({@code com.acme.ecommerce.auth.token.TokenService}); no
 * other code path may mint a token.
 *
 * <p>Package layout:
 *
 * <ul>
 *   <li>{@code web} - controllers, cookies, request context. Thin: bind, delegate, respond.
 *   <li>{@code service} - password login, registration, principal loading, lockout counters.
 *   <li>{@code oauth2} - the Google leg: stateless authorization-request storage, the
 *       link/provision decision, success/failure handling.
 *   <li>{@code token} - token issuance and the refresh rotation state machine. The refresh-token
 *       entity and repository are PACKAGE-PRIVATE here, which is what makes "all token state
 *       changes go through TokenService/RefreshTokenService" a compiler-enforced rule rather than a
 *       convention.
 *   <li>{@code domain} / {@code repository} - JPA entities and their repositories.
 *   <li>{@code security} - Spring Security's view of a user during password authentication.
 *   <li>{@code config} - properties and wiring.
 *   <li>{@code error} - auth-specific RFC 9457 problem translation.
 *   <li>{@code ratelimit}, {@code metrics} - cross-cutting controls and instrumentation.
 * </ul>
 */
package com.acme.ecommerce.auth;
