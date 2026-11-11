package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Credentials presented to POST /api/auth/login. Custom toString() masks password for security. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {
  @NotBlank
  @Email
  @Size(max = 320)
  String email;

  @NotBlank
  @Size(min = 1, max = 200)
  String password;

  @Override
  public String toString() {
    return "LoginRequest[email=" + email + ", password=***]";
  }
}
