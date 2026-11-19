package com.acme.ecommerce.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.web.net.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ForwardedHeaderConfigTest {

  private final ForwardedHeaderConfig config = new ForwardedHeaderConfig();

  private HttpServletRequest seen(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    config
        .forwardedHeaderFilter()
        .getFilter()
        .doFilter(request, new MockHttpServletResponse(), chain);
    return (HttpServletRequest) chain.getRequest();
  }

  @Test
  @DisplayName("X-Forwarded-For without X-Internal-Client-Ip: the socket peer is used")
  void peerNotForwardedFor() throws Exception {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/x");
    r.setRemoteAddr("10.0.0.9");
    r.addHeader("X-Forwarded-For", "9.9.9.9");
    HttpServletRequest seen = seen(r);
    assertThat(seen.getRemoteAddr()).isEqualTo("10.0.0.9");
    assertThat(ClientIp.resolveAsString(seen)).isEqualTo("10.0.0.9");
  }

  @Test
  @DisplayName("X-Forwarded-Host and -Proto still apply (OIDC callback URL)")
  void hostAndProtoKept() throws Exception {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/x");
    r.setRemoteAddr("10.0.0.9");
    r.addHeader("X-Forwarded-Host", "shop.example.com");
    r.addHeader("X-Forwarded-Proto", "https");
    r.addHeader("X-Forwarded-For", "9.9.9.9");
    HttpServletRequest seen = seen(r);
    assertThat(seen.getServerName()).isEqualTo("shop.example.com");
    assertThat(seen.getScheme()).isEqualTo("https");
  }
}
