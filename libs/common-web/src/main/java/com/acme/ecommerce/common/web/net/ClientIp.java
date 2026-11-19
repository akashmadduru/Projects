package com.acme.ecommerce.common.web.net;

import com.acme.ecommerce.common.core.net.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The one servlet-side answer to "who is the client". The gateway computes the address and sets
 * {@code X-Internal-Client-Ip} on proxied requests (see {@link ClientAddress#HEADER}); this reads
 * it. It never reads {@code X-Forwarded-For}, which the gateway strips and which any in-cluster
 * caller could forge. Without a usable header (direct call, health probe) it falls back to the
 * socket peer. Never throws, never does a DNS lookup.
 */
public final class ClientIp {

  /** Value returned by {@link #resolveAsString} when nothing usable is available. */
  public static final String UNKNOWN = "unknown";

  private ClientIp() {}

  /** The header address when exactly one value is present and parses, else the parsed peer. */
  public static Optional<InetAddress> resolve(HttpServletRequest request) {
    try {
      List<String> values = Collections.list(request.getHeaders(ClientAddress.HEADER));
      if (values.size() == 1) {
        Optional<InetAddress> fromHeader = ClientAddress.parse(values.get(0));
        if (fromHeader.isPresent()) {
          return fromHeader;
        }
      }
      return ClientAddress.parse(request.getRemoteAddr());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Canonical text of {@link #resolve}, or {@value #UNKNOWN}. */
  public static String resolveAsString(HttpServletRequest request) {
    return resolve(request).map(ClientAddress::format).orElse(UNKNOWN);
  }
}
