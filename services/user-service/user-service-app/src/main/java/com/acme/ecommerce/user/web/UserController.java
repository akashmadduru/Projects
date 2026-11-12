package com.acme.ecommerce.user.web;

import com.acme.ecommerce.user.api.UserAddressClient;
import com.acme.ecommerce.user.api.dto.AddressRequest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.acme.ecommerce.user.api.dto.UpdateProfileRequest;
import com.acme.ecommerce.user.api.dto.UserProfileResponse;
import com.acme.ecommerce.user.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own profile and addresses. The user id is ALWAYS the JWT subject. */
@RestController
@RequestMapping("/api/users/me")
public class UserController {

  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  @GetMapping
  public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
    return users.getProfile(userId(jwt), jwt.getClaimAsString("email"));
  }

  @PutMapping
  public UserProfileResponse update(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
    return users.updateProfile(userId(jwt), jwt.getClaimAsString("email"), request);
  }

  @GetMapping("/addresses")
  public List<AddressResponse> listAddresses(@AuthenticationPrincipal Jwt jwt) {
    return users.listAddresses(userId(jwt));
  }

  @PostMapping("/addresses")
  @ResponseStatus(HttpStatus.CREATED)
  public AddressResponse createAddress(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddressRequest request) {
    return users.createAddress(userId(jwt), request);
  }

  /** Also the endpoint behind {@link UserAddressClient#findAddress}. */
  @GetMapping("/addresses/{id}")
  public AddressResponse getAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return users.getAddress(userId(jwt), id);
  }

  @PutMapping("/addresses/{id}")
  public AddressResponse updateAddress(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody AddressRequest request) {
    return users.updateAddress(userId(jwt), id, request);
  }

  @DeleteMapping("/addresses/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    users.deleteAddress(userId(jwt), id);
  }

  @PutMapping("/addresses/{id}/default")
  public AddressResponse setDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return users.setDefault(userId(jwt), id);
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
