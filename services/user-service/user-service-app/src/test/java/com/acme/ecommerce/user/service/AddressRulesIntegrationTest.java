package com.acme.ecommerce.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.api.dto.AddressRequest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Address rules against the real schema (TD-TEST-01): one default, cap, ownership. */
class AddressRulesIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private UserService users;

  private final UUID user = UUID.randomUUID();

  private static AddressRequest address(String label) {
    return new AddressRequest(
        label,
        "Test Customer",
        "+919999999999",
        "1 MG Road",
        null,
        "Bengaluru",
        "KA",
        "560001",
        "IN");
  }

  private long defaults(List<AddressResponse> list) {
    return list.stream().filter(AddressResponse::isDefault).count();
  }

  @Test
  @DisplayName("the first address is the default; later ones are not")
  void firstIsDefault() {
    AddressResponse home = users.createAddress(user, address("Home"));
    AddressResponse work = users.createAddress(user, address("Work"));

    assertThat(home.isDefault()).isTrue();
    assertThat(work.isDefault()).isFalse();
  }

  @Test
  @DisplayName("choosing a new default leaves exactly one default")
  void exactlyOneDefault() {
    users.createAddress(user, address("Home"));
    AddressResponse work = users.createAddress(user, address("Work"));

    users.setDefault(user, work.id());

    List<AddressResponse> all = users.listAddresses(user);
    assertThat(defaults(all)).isEqualTo(1);
    assertThat(all.stream().filter(AddressResponse::isDefault).findFirst().orElseThrow().id())
        .isEqualTo(work.id());
  }

  @Test
  @DisplayName("deleting the default promotes another address")
  void deletingDefaultPromotesAnother() {
    AddressResponse home = users.createAddress(user, address("Home"));
    users.createAddress(user, address("Work"));

    users.deleteAddress(user, home.id());

    List<AddressResponse> left = users.listAddresses(user);
    assertThat(left).hasSize(1);
    assertThat(defaults(left)).isEqualTo(1);
  }

  @Test
  @DisplayName("at most 20 addresses can be saved")
  void addressCap() {
    for (int i = 0; i < 20; i++) {
      users.createAddress(user, address("A" + i));
    }

    assertThatThrownBy(() -> users.createAddress(user, address("One too many")))
        .isInstanceOf(CodedException.class)
        .satisfies(
            e -> assertThat(((CodedException) e).errorCode()).isEqualTo(ErrorCode.ADDRESS_LIMIT));
  }

  @Test
  @DisplayName("another customer's address is not found for read, update, default or delete")
  void foreignAddressIsNotFound() {
    AddressResponse theirs = users.createAddress(UUID.randomUUID(), address("Theirs"));

    for (Runnable call :
        List.<Runnable>of(
            () -> users.getAddress(user, theirs.id()),
            () -> users.updateAddress(user, theirs.id(), address("Mine now")),
            () -> users.setDefault(user, theirs.id()),
            () -> users.deleteAddress(user, theirs.id()))) {
      assertThatThrownBy(call::run)
          .isInstanceOf(CodedException.class)
          .satisfies(
              e ->
                  assertThat(((CodedException) e).errorCode())
                      .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND));
    }
  }
}
