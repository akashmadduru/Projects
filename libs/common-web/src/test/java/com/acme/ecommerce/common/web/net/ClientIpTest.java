package com.acme.ecommerce.common.web.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.net.ClientAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

  private static MockHttpServletRequest request(String remote) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/x");
    r.setRemoteAddr(remote);
    return r;
  }

  @Test
  @DisplayName("a single parseable header wins over the peer")
  void headerPresent() {
    MockHttpServletRequest r = request("10.0.0.9");
    r.addHeader(ClientAddress.HEADER, "203.0.113.7");
    assertThat(ClientIp.resolveAsString(r)).isEqualTo("203.0.113.7");
  }

  @Test
  @DisplayName("header is canonicalised")
  void canonical() {
    MockHttpServletRequest r = request("10.0.0.9");
    r.addHeader(ClientAddress.HEADER, "2001:DB8:0:0:0:0:0:1");
    assertThat(ClientIp.resolveAsString(r)).isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  @DisplayName("absent header falls back to the peer")
  void absent() {
    assertThat(ClientIp.resolveAsString(request("10.0.0.9"))).isEqualTo("10.0.0.9");
  }

  @Test
  @DisplayName("malformed, comma-joined and hostname values fall back to the peer")
  void unusable() {
    for (String bad : new String[] {"garbage", "1.1.1.1, 2.2.2.2", "example.com", ""}) {
      MockHttpServletRequest r = request("10.0.0.9");
      r.addHeader(ClientAddress.HEADER, bad);
      assertThat(ClientIp.resolveAsString(r)).as(bad).isEqualTo("10.0.0.9");
    }
  }

  @Test
  @DisplayName("two header lines are not trusted: the peer is used")
  void twoLines() {
    MockHttpServletRequest r = request("10.0.0.9");
    r.addHeader(ClientAddress.HEADER, "6.6.6.6");
    r.addHeader(ClientAddress.HEADER, "203.0.113.7");
    assertThat(ClientIp.resolveAsString(r)).isEqualTo("10.0.0.9");
  }

  @Test
  @DisplayName("X-Forwarded-For is never read")
  void xffIgnored() {
    MockHttpServletRequest r = request("10.0.0.9");
    r.addHeader("X-Forwarded-For", "9.9.9.9, 8.8.8.8");
    assertThat(ClientIp.resolveAsString(r)).isEqualTo("10.0.0.9");
  }

  @Test
  @DisplayName("null or unparseable peer without a header is empty / unknown")
  void noPeer() {
    MockHttpServletRequest r = request(null);
    assertThat(ClientIp.resolve(r)).isEmpty();
    assertThat(ClientIp.resolveAsString(r)).isEqualTo("unknown");
    assertThat(ClientIp.resolveAsString(request("not-an-ip"))).isEqualTo("unknown");
  }
}
