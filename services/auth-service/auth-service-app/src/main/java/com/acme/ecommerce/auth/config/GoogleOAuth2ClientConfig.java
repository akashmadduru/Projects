package com.acme.ecommerce.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Registers Google as an OIDC provider - ONLY when credentials are actually configured.
 *
 * <p>WHY NOT {@code spring.security.oauth2.client.registration.google.*}. Boot's own
 * auto-configuration builds the registration eagerly from those properties and calls {@code
 * ClientRegistration.Builder#build()}, which asserts a non-empty client id. Wiring {@code
 * client-id: ${GOOGLE_CLIENT_ID:}} into that namespace therefore does not "disable Google" - it
 * prevents the application from starting at all in every environment that has no Google
 * credentials, which is every developer machine and every test run. Owning the registration here
 * makes "no credentials" a supported, silent configuration rather than a startup crash.
 *
 * <p>With no bean present, {@code SecurityConfig} simply does not install {@code oauth2Login}, and
 * {@code /oauth2/authorization/google} returns 404. Password login, refresh rotation, JWKS and
 * everything else work exactly as normal - which is what makes the rest of this service fully
 * testable without a Google project.
 *
 * <p>{@link CommonOAuth2Provider#GOOGLE} supplies the endpoint URIs, the {@code openid profile
 * email} scopes and the {@code sub} user-name attribute, so none of those are restated (and cannot
 * drift) here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "!'${auth.oauth2.client-id:}'.trim().isEmpty() and !'${auth.oauth2.client-secret:}'.trim().isEmpty()")
public class GoogleOAuth2ClientConfig {

  /** Registration id; also the path segment in {@code /oauth2/authorization/{id}}. */
  public static final String REGISTRATION_ID = "google";

  private static final Logger log = LoggerFactory.getLogger(GoogleOAuth2ClientConfig.class);

  /** The single Google registration. */
  @Bean
  public ClientRegistrationRepository clientRegistrationRepository(AuthProperties properties) {
    ClientRegistration google =
        CommonOAuth2Provider.GOOGLE
            .getBuilder(REGISTRATION_ID)
            .clientId(properties.oauth2().clientId())
            .clientSecret(properties.oauth2().clientSecret())
            .build();

    log.info("Google OIDC login is ENABLED (redirect URI template {})", google.getRedirectUri());
    return new InMemoryClientRegistrationRepository(google);
  }
}
