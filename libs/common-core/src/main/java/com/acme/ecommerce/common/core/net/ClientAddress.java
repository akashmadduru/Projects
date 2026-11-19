package com.acme.ecommerce.common.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * The client-address contract shared by the reactive gateway and the servlet services: the header
 * name, a strict IP-literal parser and a canonical formatter. Framework-free on purpose.
 *
 * <p>Parsing is resolver-free: {@link InetAddress#ofLiteral} only parses IP literals and never
 * falls back to a DNS or hosts lookup, unlike {@link InetAddress#getByName}. An IPv4-mapped IPv6
 * address ({@code ::ffff:1.2.3.4}) becomes an {@code Inet4Address} and so equals the plain IPv4
 * address. Never throws.
 */
public final class ClientAddress {

  /** Set by the gateway on proxied requests only; stripped from every inbound request. */
  public static final String HEADER = "X-Internal-Client-Ip";

  private static final int MAX_ENTRY_LENGTH = 64;

  private ClientAddress() {}

  /** Parses an IP literal (optionally padded, bracketed, with a v4 port or a zone id). */
  public static Optional<InetAddress> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String s = raw.trim();
    if (s.isEmpty() || s.length() > MAX_ENTRY_LENGTH) {
      return Optional.empty();
    }
    if (s.startsWith("[")) {
      int close = s.indexOf(']');
      if (close < 0) {
        return Optional.empty();
      }
      String rest = s.substring(close + 1);
      if (!rest.isEmpty() && !(rest.startsWith(":") && isPort(rest.substring(1)))) {
        return Optional.empty();
      }
      s = s.substring(1, close);
    }
    int zone = s.indexOf('%');
    if (zone >= 0 && s.indexOf(':') >= 0) {
      s = s.substring(0, zone);
    }
    int firstColon = s.indexOf(':');
    if (firstColon >= 0 && firstColon == s.lastIndexOf(':') && s.indexOf('.') >= 0) {
      if (!isPort(s.substring(firstColon + 1))) {
        return Optional.empty();
      }
      s = s.substring(0, firstColon); // IPv4:port
    }
    if (s.isEmpty() || (s.indexOf(':') < 0 && s.chars().filter(c -> c == '.').count() != 3)) {
      return Optional.empty(); // dotted quad only: ofLiteral would also accept "1" or "127.1"
    }
    try {
      return Optional.of(InetAddress.ofLiteral(s));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean isPort(String p) {
    return p.length() >= 1 && p.length() <= 5 && p.chars().allMatch(ch -> ch >= '0' && ch <= '9');
  }

  /** Canonical, scope-free text form of an address (never a DNS lookup). */
  public static String format(InetAddress address) {
    try {
      return InetAddress.getByAddress(address.getAddress()).getHostAddress();
    } catch (UnknownHostException e) {
      return address.getHostAddress();
    }
  }
}
