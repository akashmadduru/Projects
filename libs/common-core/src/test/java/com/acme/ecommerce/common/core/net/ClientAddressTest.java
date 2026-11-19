package com.acme.ecommerce.common.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

  private static String ip(String raw) {
    return ClientAddress.parse(raw).map(InetAddress::getHostAddress).orElse(null);
  }

  @Test
  @DisplayName("padded values and ports are accepted; the port is stripped")
  void paddingAndPorts() {
    assertThat(ip(" 1.1.1.1:8080 ")).isEqualTo("1.1.1.1");
    assertThat(ip("3.3.3.3")).isEqualTo("3.3.3.3");
    assertThat(ip("[2001:db8::1]:443")).isEqualTo(ip("2001:db8::1"));
  }

  @Test
  @DisplayName("missing, blank, garbage, hostname, oversized and non-literal values are rejected")
  void rejects() {
    String big = "a".repeat(5000);
    for (String raw :
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
          "1.2.3.4::",
          "127.1",
          "1"
        }) {
      assertThat(ClientAddress.parse(raw)).as("raw=%s", raw).isEmpty();
    }
  }

  @Test
  @DisplayName("IPv6 written differently parses to the same address; v4 is not v6")
  void canonicalForms() {
    InetAddress a = ClientAddress.parse("2001:DB8::1").orElseThrow();
    for (String form :
        new String[] {
          "2001:0db8:0:0:0:0:0:1", "[2001:db8::1]", "[2001:db8::1]:443", "2001:db8::1%eth0"
        }) {
      assertThat(ClientAddress.parse(form).orElseThrow()).as(form).isEqualTo(a);
    }
    InetAddress v4 = ClientAddress.parse("1.2.3.4").orElseThrow();
    assertThat(v4).isNotEqualTo(ClientAddress.parse("::102:304").orElseThrow());
  }

  @Test
  @DisplayName("compressed and embedded-IPv4 forms parse; IPv4-mapped IPv6 equals the IPv4")
  void ipv6Forms() {
    assertThat(ip("::")).isEqualTo("0:0:0:0:0:0:0:0");
    assertThat(ip("::1")).isEqualTo("0:0:0:0:0:0:0:1");
    assertThat(ip("1:2:3:4:5:6:7:8")).isEqualTo("1:2:3:4:5:6:7:8");
    assertThat(ip("1::")).isEqualTo("1:0:0:0:0:0:0:0");
    assertThat(ClientAddress.parse("::ffff:1.2.3.4")).isEqualTo(ClientAddress.parse("1.2.3.4"));
  }

  @Test
  @DisplayName("format is canonical and scope-free")
  void format() throws Exception {
    assertThat(ClientAddress.format(ClientAddress.parse("::FFFF:1.2.3.4").orElseThrow()))
        .isEqualTo("1.2.3.4");
    assertThat(ClientAddress.format(ClientAddress.parse("2001:DB8::1%eth0").orElseThrow()))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
    Optional<InetAddress> scoped = Optional.of(InetAddress.getByName("fe80::1%1"));
    assertThat(ClientAddress.format(scoped.get())).doesNotContain("%");
    assertThat(ClientAddress.HEADER).isEqualTo("X-Internal-Client-Ip");
  }
}
