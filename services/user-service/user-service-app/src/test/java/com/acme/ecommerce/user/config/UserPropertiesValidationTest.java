package com.acme.ecommerce.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class UserPropertiesValidationTest {

  private static UserProperties with(Duration minAge, Duration lookback, Duration retention) {
    UserProperties p = new UserProperties();
    p.getReferral().setMinCodeAge(minAge);
    p.getReferral().setIpLookback(lookback);
    p.getReferral().setIpRetention(retention);
    return p;
  }

  private static Duration d(long days) {
    return Duration.ofDays(days);
  }

  @Test
  @DisplayName("a null duration is a clear startup error naming the property")
  void nullDurationsFailClearly() {
    assertThatThrownBy(() -> with(null, d(1), d(1)).validateReferral())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("user.referral.min-code-age");
    assertThatThrownBy(() -> with(d(1), null, d(1)).validateReferral())
        .hasMessageContaining("user.referral.ip-lookback");
    assertThatThrownBy(() -> with(d(1), d(1), null).validateReferral())
        .hasMessageContaining("user.referral.ip-retention");
  }

  @Test
  @DisplayName(
      "a blank environment value never reaches the service as null: the binder keeps the default")
  void blankEnvKeepsDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(Props.class)
        .withPropertyValues(
            "user.referral.ip-lookback=",
            "user.referral.min-code-age=",
            "user.referral.ip-retention=")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              UserProperties.Referral r = ctx.getBean(UserProperties.class).getReferral();
              assertThat(r.getIpLookback()).isEqualTo(Duration.ofDays(30));
              assertThat(r.getMinCodeAge()).isEqualTo(Duration.ofMinutes(10));
              assertThat(r.getIpRetention()).isEqualTo(Duration.ofDays(30));
            });
  }

  @EnableConfigurationProperties(UserProperties.class)
  static class Props {}

  @Test
  @DisplayName("zero or negative retention warns that the purge is disabled; sane values do not")
  void nonPositiveRetentionWarns() {
    Logger logger = (Logger) LoggerFactory.getLogger(UserProperties.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatCode(() -> with(d(1), d(1), Duration.ZERO).validateReferral())
          .doesNotThrowAnyException();
      with(d(1), d(1), Duration.ofSeconds(-5)).validateReferral();
      assertThat(appender.list)
          .hasSize(2)
          .allSatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("purge is DISABLED");
              });
      appender.list.clear();
      with(d(1), d(1), d(30)).validateReferral();
      assertThat(appender.list).isEmpty();
    } finally {
      logger.detachAppender(appender);
    }
  }
}
