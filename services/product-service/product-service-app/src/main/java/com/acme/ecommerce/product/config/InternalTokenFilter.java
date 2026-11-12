package com.acme.ecommerce.product.config;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.product.error.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires X-Internal-Token (constant-time compare). A blank configured token rejects all. */
public class InternalTokenFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Internal-Token";

  private final byte[] expected;
  private final ProblemResponseWriter problems;

  public InternalTokenFilter(String token, ProblemResponseWriter problems) {
    this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    boolean ok =
        expected.length > 0
            && supplied != null
            && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected);
    if (!ok) {
      problems.write(request, response, ErrorCode.INTERNAL_AUTH_FAILED, "Internal token required");
      return;
    }
    chain.doFilter(request, response);
  }
}
