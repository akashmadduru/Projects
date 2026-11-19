package com.acme.ecommerce.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.net.ClientAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestDeviceContextTest {

  private final RequestDeviceContext context = new RequestDeviceContext();

  @Test
  @DisplayName("the gateway header wins over the peer and over a spoofed X-Forwarded-For")
  void headerWins() {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRemoteAddr("10.0.0.9");
    r.addHeader(ClientAddress.HEADER, "203.0.113.7");
    r.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
    assertThat(context.clientIp(r)).isEqualTo("203.0.113.7");
    assertThat(context.from(r).ipAddress()).isEqualTo("203.0.113.7");
  }

  @Test
  @DisplayName("without the header the peer is used and X-Forwarded-For is ignored")
  void peerFallback() {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRemoteAddr("10.0.0.9");
    r.addHeader("X-Forwarded-For", "9.9.9.9");
    assertThat(context.clientIp(r)).isEqualTo("10.0.0.9");
  }

  @Test
  @DisplayName("never null")
  void neverNull() {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRemoteAddr(null);
    assertThat(context.clientIp(r)).isEqualTo("unknown");
  }
}
