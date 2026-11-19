package com.acme.ecommerce.payment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.common.core.net.ClientAddress;
import com.acme.ecommerce.payment.service.GiftCardService;
import com.acme.ecommerce.payment.service.PaymentService;
import com.acme.ecommerce.payment.service.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

class WalletControllerClientIpTest {

  private final GiftCardService giftCards = mock(GiftCardService.class);
  private final WalletController controller =
      new WalletController(mock(WalletService.class), giftCards, mock(PaymentService.class));
  private final UUID user = UUID.randomUUID();
  private final Jwt jwt =
      Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();

  private void redeem(MockHttpServletRequest http) {
    controller.redeem(jwt, new WalletController.RedeemRequest("CODE"), http);
  }

  private MockHttpServletRequest request(String xff) {
    MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/gift-cards/redeem");
    http.setRemoteAddr("10.0.0.9");
    http.addHeader(ClientAddress.HEADER, "203.0.113.7");
    http.addHeader("X-Forwarded-For", xff);
    return http;
  }

  @Test
  @DisplayName("per-IP key is the gateway header; rotating X-Forwarded-For keeps one bucket")
  void rotatingXffSameKey() {
    redeem(request("9.9.9.9, 203.0.113.7"));
    redeem(request("8.8.8.8"));
    redeem(request("7.7.7.7"));
    verify(giftCards, times(3)).redeem(eq(user), eq("CODE"), eq("203.0.113.7"));
  }

  @Test
  @DisplayName("without header or peer the key is null, not a shared 'unknown' bucket")
  void unknownIsNull() {
    MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/gift-cards/redeem");
    http.setRemoteAddr(null);
    redeem(http);
    verify(giftCards).redeem(eq(user), eq("CODE"), eq((String) null));
    verify(giftCards, times(0)).redeem(any(), any());
  }
}
