/**
 * Spring Data repositories for the auth domain.
 *
 * <p>NOTE THE ABSENCE OF {@code RefreshTokenRepository}. It lives in {@code
 * com.acme.ecommerce.auth.token} and is package-private, so refresh-token state can only be changed
 * through {@code TokenService} / {@code RefreshTokenService}. Adding a refresh-token repository
 * here would defeat that.
 */
package com.acme.ecommerce.auth.repository;
