package com.acme.ecommerce.seller.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.seller.client.AuthFeignClient;
import com.acme.ecommerce.seller.client.NotificationFeignClient;
import com.acme.ecommerce.seller.client.ProductFeignClient;
import com.acme.ecommerce.seller.crypto.DataCipher;
import com.acme.ecommerce.seller.seller.SellerService.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** TD-P9-09: suspension revokes the seller role; KYC numbers are masked. */
class SellerServiceSuspendTest {

  private final UUID sellerId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private JdbcClient jdbc;
  private AuthFeignClient auth;
  private SellerService service;

  private Seller seller(String status) {
    return new Seller(
        sellerId,
        userId,
        "Acme Traders Pvt Ltd",
        "Acme",
        "acme",
        "a@b.c",
        null,
        "27ABCDE1234F1Z5",
        "ABCDE1234F",
        "6789",
        "HDFC0001234",
        null,
        status,
        null,
        BigDecimal.TEN,
        null,
        0,
        OffsetDateTime.now(),
        null);
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    auth = mock(AuthFeignClient.class);
    service =
        new SellerService(
            jdbc,
            mock(DataCipher.class),
            auth,
            mock(NotificationFeignClient.class),
            mock(ProductFeignClient.class),
            "http://admin");
    when(jdbc.sql(startsWith("SELECT * FROM sellers WHERE id"))
            .param(anyString(), any())
            .query(any(RowMapper.class))
            .optional())
        .thenReturn(Optional.of(seller("APPROVED")));
  }

  @Test
  @DisplayName("suspending an approved seller revokes the SELLER role")
  void suspendRevokesRole() {
    service.suspend(sellerId, "Fake listings");

    verify(auth).revokeRole(userId, "SELLER");
  }

  @Test
  @DisplayName(
      "if the role cannot be revoked the suspension fails (and its transaction rolls back)")
  void revokeFailureFailsSuspension() {
    doThrow(new IllegalStateException("auth down")).when(auth).revokeRole(any(), anyString());

    assertThatThrownBy(() -> service.suspend(sellerId, "Fake listings"))
        .isInstanceOf(CodedException.class);
  }

  @Test
  @DisplayName("masking keeps the first and last two characters")
  void masking() {
    Seller masked = seller("APPROVED").masked();

    assertThat(masked.gstin()).isEqualTo("27***********Z5");
    assertThat(masked.pan()).isEqualTo("AB******4F");
    assertThat(masked.bankAccountLast4()).isEqualTo("6789");
    assertThat(Seller.mask(null)).isNull();
    assertThat(Seller.mask("ABC")).isEqualTo("***");
  }
}
