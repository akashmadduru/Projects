package com.acme.ecommerce.cart.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Stores the caller's JWT email on the cart after every successful cart mutation, so the hourly
 * abandoned-cart job (no JWT available) knows where to send the reminder.
 */
@Configuration(proxyBeanMethods = false)
public class EmailCaptureConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(EmailCaptureConfig.class);

  private final JdbcClient jdbc;

  public EmailCaptureConfig(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public void afterCompletion(
                  HttpServletRequest request,
                  HttpServletResponse response,
                  Object handler,
                  Exception ex) {
                if ("GET".equals(request.getMethod())
                    || response.getStatus() >= 400
                    || ex != null) {
                  return;
                }
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
                  return;
                }
                String email = jwt.getClaimAsString("email");
                if (email == null || email.isBlank()) {
                  return;
                }
                try {
                  jdbc.sql(
                          "UPDATE cart.carts SET email = :e WHERE user_id = :u AND email IS"
                              + " DISTINCT FROM :e")
                      .param("e", email)
                      .param("u", UUID.fromString(jwt.getSubject()))
                      .update();
                } catch (RuntimeException e) {
                  log.debug("Cart email capture failed: {}", e.toString());
                }
              }
            })
        .addPathPatterns("/api/cart/**");
  }
}
