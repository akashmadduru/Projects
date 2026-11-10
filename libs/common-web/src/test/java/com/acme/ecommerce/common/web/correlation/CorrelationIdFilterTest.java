package com.acme.ecommerce.common.web.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  @DisplayName("honours a valid inbound correlation id so one id spans every hop")
  void honoursInboundId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader(CorrelationIdHolder.HEADER_NAME, "abc-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    String[] observed = new String[1];
    filter.doFilter(request, response, capture(observed));

    assertThat(observed[0]).isEqualTo("abc-123");
    assertThat(response.getHeader(CorrelationIdHolder.HEADER_NAME)).isEqualTo("abc-123");
  }

  @Test
  @DisplayName("generates an id when the request arrives without one")
  void generatesWhenAbsent() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    String[] observed = new String[1];
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/products"), response, capture(observed));

    assertThat(observed[0]).isNotBlank();
    assertThat(response.getHeader(CorrelationIdHolder.HEADER_NAME)).isEqualTo(observed[0]);
  }

  @Test
  @DisplayName("rejects a CRLF-injecting id instead of echoing it (log forging / header splitting)")
  void rejectsCrlfInjection() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader(CorrelationIdHolder.HEADER_NAME, "evil\r\nSet-Cookie: admin=true");
    MockHttpServletResponse response = new MockHttpServletResponse();

    String[] observed = new String[1];
    filter.doFilter(request, response, capture(observed));

    assertThat(observed[0]).doesNotContain("Set-Cookie").doesNotContain("\r").doesNotContain("\n");
    assertThat(response.getHeader(CorrelationIdHolder.HEADER_NAME)).isEqualTo(observed[0]);
  }

  @Test
  @DisplayName("rejects an over-long id")
  void rejectsOverLongId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader(CorrelationIdHolder.HEADER_NAME, "x".repeat(500));

    String[] observed = new String[1];
    filter.doFilter(request, new MockHttpServletResponse(), capture(observed));

    assertThat(observed[0]).hasSizeLessThanOrEqualTo(64);
  }

  @Test
  @DisplayName(
      "clears the ThreadLocal and MDC after the request so ids cannot leak between requests")
  void clearsAfterRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader(CorrelationIdHolder.HEADER_NAME, "abc-123");

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    assertThat(CorrelationIdHolder.get()).isEmpty();
    assertThat(MDC.get(CorrelationIdHolder.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("clears state even when the downstream chain throws")
  void clearsAfterException() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");

    try {
      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (req, res) -> {
            throw new IllegalStateException("boom");
          });
    } catch (Exception expected) {
      // the filter must not swallow it
    }

    assertThat(CorrelationIdHolder.get()).isEmpty();
    assertThat(MDC.get(CorrelationIdHolder.MDC_KEY)).isNull();
  }

  private static FilterChain capture(String[] target) {
    return (req, res) -> {
      target[0] = CorrelationIdHolder.get().orElse(null);
      // MDC must be populated *during* the request, which is what logging depends on.
      assertThat(MDC.get(CorrelationIdHolder.MDC_KEY)).isEqualTo(target[0]);
    };
  }
}
