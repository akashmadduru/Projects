package com.acme.ecommerce.user.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the caller's IP from the first {@code X-Forwarded-For} entry, else the remote address.
 * Parses literals only (never a DNS lookup), canonicalises via {@link InetAddress}, never throws.
 */
public final class ClientIp {

  private static final int MAX_ENTRY_LENGTH = 64;
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
  private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

  private ClientIp() {}

  public static Optional<InetAddress> resolve(HttpServletRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null) {
      int comma = forwarded.indexOf(',');
      Optional<InetAddress> first = parse(comma < 0 ? forwarded : forwarded.substring(0, comma));
      if (first.isPresent()) {
        return first;
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
    boolean literal =
        s.indexOf(':') >= 0 ? IPV6_CHARS.matcher(s).matches() : IPV4.matcher(s).matches();
    if (!literal) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByName(s));
    } catch (java.io.IOException | RuntimeException e) {
      return Optional.empty();
    }
  }
}
