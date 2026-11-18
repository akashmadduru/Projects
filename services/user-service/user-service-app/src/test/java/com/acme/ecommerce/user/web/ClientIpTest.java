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

  private static Optional<InetAddress> resolve(MockHttpServletRequest r) {
    return ClientIp.resolve(r, 1);
  }

  private static String ip(Optional<InetAddress> a) {
    return a.map(InetAddress::getHostAddress).orElse(null);
  }

  @Test
  @DisplayName("with hops=1 the client is the last entry; ports are stripped")
  void lastEntryForOneHop() {
    assertThat(ip(resolve(request("1.1.1.1, 2.2.2.2, 3.3.3.3", "9.9.9.9")))).isEqualTo("3.3.3.3");
    assertThat(ip(resolve(request("2.2.2.2,  1.1.1.1:8080 ", "9.9.9.9")))).isEqualTo("1.1.1.1");
  }

  @Test
  @DisplayName("spoofed, real-client, alb, gateway with hops=3 resolves to real-client")
  void spoofedLeftEntryIgnored() {
    String xff = "6.6.6.6, 203.0.113.50, 10.0.1.1, 10.0.2.2";
    assertThat(ip(ClientIp.resolve(request(xff, "9.9.9.9"), 3))).isEqualTo("203.0.113.50");
    // a longer forged prefix changes nothing
    assertThat(ip(ClientIp.resolve(request("7.7.7.7, 8.8.8.8, " + xff, "9.9.9.9"), 3)))
        .isEqualTo("203.0.113.50");
  }

  @Test
  @DisplayName("hops=0 or fewer entries than hops fall back to the socket peer")
  void zeroHopsOrTooFewEntries() {
    assertThat(ip(ClientIp.resolve(request("1.1.1.1, 2.2.2.2", "9.9.9.9"), 0)))
        .isEqualTo("9.9.9.9");
    assertThat(ip(ClientIp.resolve(request("1.1.1.1, 2.2.2.2", "9.9.9.9"), -1)))
        .isEqualTo("9.9.9.9");
    assertThat(ip(ClientIp.resolve(request("1.1.1.1, 2.2.2.2", "9.9.9.9"), 3)))
        .isEqualTo("9.9.9.9");
    assertThat(ip(ClientIp.resolve(request(null, "9.9.9.9"), 2))).isEqualTo("9.9.9.9");
  }

  @Test
  @DisplayName("a non-literal selected entry falls back to the socket peer, not to its neighbour")
  void nonLiteralSelectedEntry() {
    assertThat(ip(ClientIp.resolve(request("1.1.1.1, garbage, 3.3.3.3", "9.9.9.9"), 2)))
        .isEqualTo("9.9.9.9");
  }

  @Test
  @DisplayName(
      "missing, blank, garbage, hostname, oversized and non-literal headers fall back to remote")
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
          "1.1.1.1, garbage",
          "999.1.1.1",
          "1.1.1.256",
          "[1.1.1.1",
          ".:1",
          "..:",
          ":.",
          "a:1",
          "1:2:3:4:5:6:7:8:9",
          "1::2::3",
          ":::",
          "1:2:3:4:5:6:7:1.2.3.4",
          "::1.2.3.999",
          "1.2.3.4::"
        }) {
      assertThat(ip(resolve(request(xff, "9.9.9.9")))).as("xff=%s", xff).isEqualTo("9.9.9.9");
    }
  }

  @Test
  @DisplayName("both sources unusable gives empty and never throws")
  void bothBad() {
    assertThat(resolve(request("garbage", "also-bad"))).isEmpty();
    assertThat(resolve(request(null, ""))).isEmpty();
    assertThat(ClientIp.resolve(null, 1)).isEmpty();
  }

  @Test
  @DisplayName("IPv6 written differently resolves to the same address; v4 is not v6")
  void canonicalForms() {
    InetAddress a = resolve(request("2001:DB8::1", "9.9.9.9")).orElseThrow();
    for (String form :
        new String[] {
          "2001:0db8:0:0:0:0:0:1", "[2001:db8::1]", "[2001:db8::1]:443", "2001:db8::1%eth0"
        }) {
      assertThat(resolve(request(form, "9.9.9.9")).orElseThrow()).as(form).isEqualTo(a);
    }
    assertThat(resolve(request(null, "2001:db8::1")).orElseThrow()).isEqualTo(a);
    InetAddress v4 = resolve(request("1.2.3.4", "9.9.9.9")).orElseThrow();
    assertThat(v4).isNotEqualTo(resolve(request("::102:304", "9.9.9.9")).orElseThrow());
  }

  @Test
  @DisplayName("valid compressed and embedded-IPv4 forms parse; IPv4-mapped IPv6 equals the IPv4")
  void ipv6Forms() {
    assertThat(ip(resolve(request("::", "9.9.9.9")))).isEqualTo("0:0:0:0:0:0:0:0");
    assertThat(ip(resolve(request("::1", "9.9.9.9")))).isEqualTo("0:0:0:0:0:0:0:1");
    assertThat(ip(resolve(request("1:2:3:4:5:6:7:8", "9.9.9.9")))).isEqualTo("1:2:3:4:5:6:7:8");
    assertThat(ip(resolve(request("1::", "9.9.9.9")))).isEqualTo("1:0:0:0:0:0:0:0");
    assertThat(resolve(request("::ffff:1.2.3.4", "9.9.9.9")).orElseThrow())
        .isEqualTo(resolve(request("1.2.3.4", "9.9.9.9")).orElseThrow());
  }
}
