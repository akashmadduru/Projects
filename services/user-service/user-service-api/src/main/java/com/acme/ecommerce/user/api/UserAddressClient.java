package com.acme.ecommerce.user.api;

import com.acme.ecommerce.user.api.dto.AddressResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative client for the part of user-service other services may call. Not annotated
 * {@code @FeignClient}: the consumer supplies that. The caller's bearer token is relayed by
 * common-web, so the address is always resolved for the token's own user.
 */
public interface UserAddressClient {

  String BASE_PATH = "/api/users/me/addresses";

  @GetMapping(value = BASE_PATH + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  AddressResponse findAddress(@PathVariable("id") UUID id);
}
