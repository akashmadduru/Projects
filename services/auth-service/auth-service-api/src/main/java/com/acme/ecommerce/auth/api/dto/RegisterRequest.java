package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Self-service sign-up payload for POST /api/auth/register. Custom toString() masks password for
 * security.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
  @NotBlank
  @Email
  @Size(max = 320)
  String email;

  @NotBlank
  @Size(min = 12, max = 200)
  String password;

  @NotBlank
  @Size(max = 200)
  String displayName;

  @Override
  public String toString() {
    return "RegisterRequest[email=" + email + ", password=***, displayName=" + displayName + "]";
  }
}
