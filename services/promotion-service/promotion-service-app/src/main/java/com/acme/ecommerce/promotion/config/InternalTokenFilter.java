package com.acme.ecommerce.promotion.config;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.promotion.error.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Guards /internal/** with the shared X-Internal-Token (constant-time compare). */
public class InternalTokenFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Internal-Token";

  private final byte[] expected;
  private final ProblemResponseWriter problems;

  public InternalTokenFilter(String token, ProblemResponseWriter problems) {
    this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String provided = request.getHeader(HEADER);
    boolean ok =
        expected.length > 0
            && provided != null
            && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
    if (!ok) {
      problems.write(
          request, response, ErrorCode.INTERNAL_AUTH_FAILED, "Internal authentication failed");
      return;
    }
    chain.doFilter(request, response);
  }
}
