package com.acme.ecommerce.auth.privacy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 10 privacy: lets user-service confirm a password (or learn the account has none) before it
 * schedules an account deletion. Internal token only; never routed by the gateway.
 */
@RestController
public class PrivacyVerifyController {

  public record VerifyRequest(String password) {}

  private final JdbcClient jdbc;
  private final PasswordEncoder encoder;

  public PrivacyVerifyController(JdbcClient jdbc, PasswordEncoder encoder) {
    this.jdbc = jdbc;
    this.encoder = encoder;
  }

  @PostMapping("/internal/privacy/users/{userId}/verify-password")
  public Map<String, Boolean> verify(@PathVariable UUID userId, @RequestBody VerifyRequest body) {
    Optional<String> hash =
        jdbc.sql("SELECT password_hash FROM auth.user_credentials WHERE user_id = :u")
            .param("u", userId)
            .query(String.class)
            .optional();
    boolean valid =
        hash.isPresent()
            && body != null
            && body.password() != null
            && !body.password().isBlank()
            && encoder.matches(body.password(), hash.get());
    return Map.of("hasPassword", hash.isPresent(), "valid", valid);
  }
}
