package com.acme.ecommerce.common.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.net.ClientAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AuditInterceptorTest {

  private static final class Capturing extends AuditPublisher {
    final List<String> ips = new ArrayList<>();

    Capturing() {
      super("localhost:0", "test");
    }

    @Override
    void publishNow(Actor a, String action, String type, String id, String after, String ip) {
      ips.add(ip);
    }
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("audit IP is the gateway header, not a client-supplied X-Forwarded-For")
  void usesGatewayHeader() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    Capturing publisher = new Capturing();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/things");
    request.setRemoteAddr("10.0.0.9");
    request.addHeader(ClientAddress.HEADER, "203.0.113.7");
    request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");

    new AuditInterceptor(publisher)
        .afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

    assertThat(publisher.ips).containsExactly("203.0.113.7");
  }
}
