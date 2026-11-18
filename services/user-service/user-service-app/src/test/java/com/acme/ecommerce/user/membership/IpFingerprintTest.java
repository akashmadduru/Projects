package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class IpFingerprintTest {

  private static InetAddress ip(String literal) throws Exception {
    return InetAddress.getByName(literal);
  }

  @Test
  @DisplayName("deterministic, 64 lowercase hex, and never contains the dotted IP")
  void deterministicHex() throws Exception {
    IpFingerprint f = new IpFingerprint("salt-a");
    String h = f.hash(ip("203.0.113.7"));
    assertThat(h).matches("^[0-9a-f]{64}$").doesNotContain("203.0.113.7");
    assertThat(new IpFingerprint("salt-a").hash(ip("203.0.113.7"))).isEqualTo(h);
    assertThat(f.hash(ip("203.0.113.8"))).isNotEqualTo(h);
  }

  @Test
  @DisplayName("different salts give different hashes")
  void saltMatters() throws Exception {
    assertThat(new IpFingerprint("a").hash(ip("203.0.113.7")))
        .isNotEqualTo(new IpFingerprint("b").hash(ip("203.0.113.7")));
  }

  @Test
  @DisplayName("v4 and v6 with equal address bytes differ")
  void familySeparated() throws Exception {
    IpFingerprint f = new IpFingerprint("salt");
    InetAddress v4 = InetAddress.getByAddress(new byte[] {1, 2, 3, 4});
    InetAddress v6 = InetAddress.getByAddress(new byte[16]);
    InetAddress v6b =
        InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4});
    assertThat(f.hash(v4)).isNotEqualTo(f.hash(v6b));
    assertThat(f.hash(v6)).isNotEqualTo(f.hash(v6b));
  }

  @Test
  @DisplayName("blank salt still hashes stably within one instance but not across instances")
  void blankSalt() throws Exception {
    IpFingerprint a = new IpFingerprint("");
    assertThat(a.hash(ip("203.0.113.7"))).isEqualTo(a.hash(ip("203.0.113.7"))).hasSize(64);
    assertThat(new IpFingerprint("  ").hash(ip("203.0.113.7")))
        .isNotEqualTo(a.hash(ip("203.0.113.7")));
  }

  private static List<String> warningsFor(String salt) {
    Logger logger = (Logger) LoggerFactory.getLogger(IpFingerprint.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new IpFingerprint(salt);
      return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("a salt under 32 bytes warns at startup without failing; a long one is silent")
  void shortSaltWarns() {
    List<String> weak = warningsFor("short-salt");
    assertThat(weak).hasSize(1);
    assertThat(weak.get(0)).contains("shorter than 32 bytes").doesNotContain("short-salt");
    assertThat(warningsFor("x".repeat(32))).isEmpty();
  }
}
