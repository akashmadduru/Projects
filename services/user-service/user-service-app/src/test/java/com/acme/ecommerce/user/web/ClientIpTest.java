package com.acme.ecommerce.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

  private static MockHttpServletRequest request(String xff, String remote) {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRemoteAddr(remote);
    if (xff != null) {
      r.addHeader("X-Forwarded-For", xff);
    }
    return r;
  }

  private static String ip(Optional<InetAddress> a) {
    return a.map(InetAddress::getHostAddress).orElse(null);
  }

  @Test
  @DisplayName("takes the first X-Forwarded-For entry")
  void firstEntry() {
    assertThat(ip(ClientIp.resolve(request("1.1.1.1, 2.2.2.2, 3.3.3.3", "9.9.9.9"))))
        .isEqualTo("1.1.1.1");
    assertThat(ip(ClientIp.resolve(request("  1.1.1.1:8080 ,2.2.2.2", "9.9.9.9"))))
        .isEqualTo("1.1.1.1");
  }

  @Test
  @DisplayName("missing, blank, garbage, hostname and oversized headers fall back to remote")
  void fallsBack() {
    String big = "a".repeat(5000);
    for (String xff :
        new String[] {
          null,
          "",
          "   ",
          "garbage",
          "example.com",
          "deadbeef",
          big,
          "garbage, 1.1.1.1",
          "999.1.1.1",
          "[1.1.1.1"
        }) {
      assertThat(ip(ClientIp.resolve(request(xff, "9.9.9.9"))))
          .as("xff=%s", xff)
          .isEqualTo("9.9.9.9");
    }
  }

  @Test
  @DisplayName("both sources unusable gives empty and never throws")
  void bothBad() {
    assertThat(ClientIp.resolve(request("garbage", "also-bad"))).isEmpty();
    assertThat(ClientIp.resolve(request(null, ""))).isEmpty();
    assertThat(ClientIp.resolve(null)).isEmpty();
  }

  @Test
  @DisplayName("IPv6 written differently resolves to the same address; v4 is not v6")
  void canonicalForms() {
    InetAddress a = ClientIp.resolve(request("2001:DB8::1", "9.9.9.9")).orElseThrow();
    for (String form :
        new String[] {
          "2001:0db8:0:0:0:0:0:1", "[2001:db8::1]", "[2001:db8::1]:443", "2001:db8::1%eth0"
        }) {
      assertThat(ClientIp.resolve(request(form, "9.9.9.9")).orElseThrow()).as(form).isEqualTo(a);
    }
    assertThat(ClientIp.resolve(request(null, "2001:db8::1")).orElseThrow()).isEqualTo(a);
    InetAddress v4 = ClientIp.resolve(request("1.2.3.4", "9.9.9.9")).orElseThrow();
    assertThat(v4).isNotEqualTo(ClientIp.resolve(request("::102:304", "9.9.9.9")).orElseThrow());
  }
}
