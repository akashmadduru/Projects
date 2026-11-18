package com.acme.ecommerce.user.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the caller's IP.
 *
 * <p>Each trusted proxy appends the address it received the request from to {@code
 * X-Forwarded-For}, so only entries on the left can be forged by the caller. With {@code hops} = N
 * (> 0) the client is the N-th entry from the RIGHT, mirroring the gateway's {@code
 * RateLimitWebFilter.remoteIp}. N = 0, fewer than N entries, or an entry that is not an IP literal
 * falls back to the socket peer. Never throws.
 *
 * <p>Parsing is resolver-free: no input ever reaches {@link InetAddress#getByName}, which would
 * fall back to a DNS or hosts lookup for anything it does not recognise as a literal. Addresses are
 * built from bytes with {@link InetAddress#getByAddress(byte[])}. An IPv4-mapped IPv6 address
 * ({@code ::ffff:1.2.3.4}) becomes an {@code Inet4Address} and so equals the plain IPv4 address.
 */
public final class ClientIp {

  private static final int MAX_ENTRY_LENGTH = 64;
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
  private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");
  private static final Pattern HEX_GROUP = Pattern.compile("^[0-9A-Fa-f]{1,4}$");

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
    byte[] bytes;
    if (s.indexOf(':') >= 0) {
      char first = s.charAt(0);
      if (Character.digit(first, 16) < 0 && first != ':') {
        return Optional.empty();
      }
      bytes = IPV6_CHARS.matcher(s).matches() ? ipv6(s) : null;
    } else {
      bytes = ipv4(s);
    }
    if (bytes == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByAddress(bytes));
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  private static byte[] ipv4(String s) {
    if (!IPV4.matcher(s).matches()) {
      return null;
    }
    String[] octets = s.split("\\.");
    byte[] out = new byte[4];
    for (int i = 0; i < 4; i++) {
      int v = Integer.parseInt(octets[i]);
      if (v > 255) {
        return null;
      }
      out[i] = (byte) v;
    }
    return out;
  }

  /** Strict RFC 4291 text form: eight groups, one optional "::", optional trailing IPv4. */
  private static byte[] ipv6(String s) {
    if (s.contains(":::")) {
      return null;
    }
    int gap = s.indexOf("::");
    if (gap != s.lastIndexOf("::")) {
      return null;
    }
    String head = gap < 0 ? s : s.substring(0, gap);
    String tail = gap < 0 ? "" : s.substring(gap + 2);
    int[] headWords = new int[8];
    int[] tailWords = new int[8];
    int headCount = words(head, gap < 0, headWords);
    int tailCount = gap < 0 ? 0 : words(tail, true, tailWords);
    if (headCount < 0 || tailCount < 0) {
      return null;
    }
    int total = headCount + tailCount;
    if (gap < 0 ? total != 8 : total > 7) {
      return null;
    }
    byte[] out = new byte[16];
    for (int i = 0; i < headCount; i++) {
      out[2 * i] = (byte) (headWords[i] >> 8);
      out[2 * i + 1] = (byte) headWords[i];
    }
    for (int i = 0; i < tailCount; i++) {
      int pos = 8 - tailCount + i;
      out[2 * pos] = (byte) (tailWords[i] >> 8);
      out[2 * pos + 1] = (byte) tailWords[i];
    }
    return out;
  }

  /** Parses colon-separated groups into 16-bit words; the last may be dotted IPv4 if allowed. */
  private static int words(String part, boolean mayEndWithIpv4, int[] out) {
    if (part.isEmpty()) {
      return 0;
    }
    String[] groups = part.split(":", -1);
    int n = 0;
    for (int i = 0; i < groups.length; i++) {
      String g = groups[i];
      if (g.indexOf('.') >= 0) {
        byte[] v4 = i == groups.length - 1 && mayEndWithIpv4 ? ipv4(g) : null;
        if (v4 == null || n + 2 > out.length) {
          return -1;
        }
        out[n++] = ((v4[0] & 0xff) << 8) | (v4[1] & 0xff);
        out[n++] = ((v4[2] & 0xff) << 8) | (v4[3] & 0xff);
      } else if (HEX_GROUP.matcher(g).matches() && n < out.length) {
        out[n++] = Integer.parseInt(g, 16);
      } else {
        return -1;
      }
    }
    return n;
  }
}
