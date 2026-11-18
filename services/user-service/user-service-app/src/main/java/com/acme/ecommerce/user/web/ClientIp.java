package com.acme.ecommerce.user.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Resolves the caller's IP.
 *
 * <p>Each trusted proxy appends the address it received the request from to {@code
 * X-Forwarded-For}, so only entries on the left can be forged by the caller. With {@code hops} = N
 * (> 0) the client is the N-th entry from the RIGHT, mirroring the gateway's {@code
 * RateLimitWebFilter.remoteIp}. N = 0, fewer than N entries, or an entry that is not an IP literal
 * falls back to the socket peer. Never throws.
 *
 * <p>Parsing is resolver-free: {@link InetAddress#ofLiteral} only parses IP literals and never
 * falls back to a DNS or hosts lookup, unlike {@link InetAddress#getByName}. An IPv4-mapped IPv6
 * address ({@code ::ffff:1.2.3.4}) becomes an {@code Inet4Address} and so equals the plain IPv4
 * address.
 */
public final class ClientIp {

  private static final int MAX_ENTRY_LENGTH = 64;

  private ClientIp() {}

  public static Optional<InetAddress> resolve(HttpServletRequest request, int hops) {
    if (request == null) {
      return Optional.empty();
    }
    if (hops > 0) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null) {
        String[] parts = forwarded.split(",");
        if (parts.length >= hops) {
          Optional<InetAddress> client = parse(parts[parts.length - hops]);
          if (client.isPresent()) {
            return client;
          }
        }
      }
    }
    return parse(request.getRemoteAddr());
  }

  private static Optional<InetAddress> parse(String raw) {
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
      s = s.substring(1, close);
    }
    int zone = s.indexOf('%');
    if (zone >= 0) {
      s = s.substring(0, zone);
    }
    int firstColon = s.indexOf(':');
    if (firstColon >= 0 && firstColon == s.lastIndexOf(':') && s.indexOf('.') >= 0) {
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
}
