package com.acme.ecommerce.common.web.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.kafka.support.ExceptionMatcher;

/**
 * Malformed events (IllegalArgumentException) go to the DLT at once; other failures are retried.
 */
class DlqErrorHandlersTest {

  @Test
  @DisplayName("an IllegalArgumentException is not retried, an ordinary failure is")
  void dlqHandlerDoesNotRetryIllegalArgument() throws Exception {
    DefaultErrorHandler handler = DlqErrorHandlers.create("localhost:9092");
    Method getter = ExceptionClassifier.class.getDeclaredMethod("getExceptionMatcher");
    getter.setAccessible(true);
    ExceptionMatcher matcher = (ExceptionMatcher) getter.invoke(handler);

    // match(...) is true when the exception is retryable
    assertThat(matcher.match(new IllegalArgumentException("malformed"))).isFalse();
    assertThat(matcher.match(new IllegalStateException("db down"))).isTrue();
  }
}
