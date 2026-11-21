package com.acme.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class UuidSubjectValidatorTest {

  private final UuidSubjectValidator validator = new UuidSubjectValidator();

  @Test
  void acceptsUuidSubject() {
    assertThat(validator.validate(jwt(UUID.randomUUID().toString())).hasErrors()).isFalse();
  }

  @Test
  void rejectsNonUuidSubject() {
    assertThat(validator.validate(jwt("user-1")).hasErrors()).isTrue();
    // UUID.fromString accepts short groups like "1-1-1-1-1"; the length check rejects them.
    assertThat(validator.validate(jwt("1-1-1-1-1")).hasErrors()).isTrue();
    assertThat(validator.validate(jwt("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz")).hasErrors())
        .isTrue();
  }

  @Test
  void rejectsMissingSubject() {
    assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
  }

  private static Jwt jwt(String subject) {
    Jwt.Builder b =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("scope", "x")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
    if (subject != null) {
      b.subject(subject);
    }
    return b.build();
  }
}
