package com.acme.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** AC-10: one owner notice per window, atomic across threads, fail-closed and non-throwing. */
class PhoneClaimNotifierRedisTest {

  private static final String PHONE = "+919876543210";

  @SuppressWarnings("resource") // stopped in tearDownClass
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;

  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final NotificationGateway notifications = mock(NotificationGateway.class);
  private final UserAccount owner = UserAccount.create("owner@example.com", "Owner", true);
  private ListAppender<ILoggingEvent> logs;

  @BeforeAll
  static void startRedis() {
    REDIS.start();
    factory = connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
  }

  @AfterAll
  static void stopRedis() {
    factory.destroy();
    REDIS.stop();
  }

  @BeforeEach
  void setUp() {
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    when(users.findWithRolesByPhoneAndPhoneVerifiedTrue(PHONE)).thenReturn(Optional.of(owner));
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(PhoneClaimNotifier.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(PhoneClaimNotifier.class)).detachAppender(logs);
  }

  private PhoneClaimNotifier notifier(StringRedisTemplate template) {
    return new PhoneClaimNotifier(
        users,
        template,
        notifications,
        new AuthProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new AuthProperties.Storefront("https://shop.test")),
        Duration.ofHours(24));
  }

  @Test
  void twentyConcurrentAttemptsSendExactlyOneEmail() throws Exception {
    PhoneClaimNotifier notifier = notifier(redis);
    ExecutorService pool = Executors.newFixedThreadPool(20);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(20);
    for (int i = 0; i < 20; i++) {
      pool.execute(
          () -> {
            try {
              start.await();
              notifier.notifyOwner(PHONE);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();

    verify(notifications, times(1))
        .sendEmail(
            eq("owner@example.com"),
            eq(EmailTemplate.PHONE_CLAIM_ATTEMPT),
            eq(
                Map.of(
                    "name", "Owner",
                    "phoneTail", "3210",
                    "link", "https://shop.test/account/security")));
  }

  @Test
  void keyHoldsNoPhoneNumberAndExpiresAboutADayOut() {
    notifier(redis).notifyOwner(PHONE);

    String key = "auth:phone-claim-notice:" + owner.getId();
    assertThat(redis.keys("*")).containsExactly(key);
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    assertThat(ttl).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
  }

  @Test
  void anotherNoticeIsSentOnceTheKeyIsGone() {
    PhoneClaimNotifier notifier = notifier(redis);
    notifier.notifyOwner(PHONE);
    notifier.notifyOwner(PHONE);
    verify(notifications, times(1)).sendEmail(any(), any(), anyMap());

    redis.delete("auth:phone-claim-notice:" + owner.getId());
    notifier.notifyOwner(PHONE);

    verify(notifications, times(2)).sendEmail(any(), any(), anyMap());
  }

  @Test
  void asyncEntryPointDeliversToo() {
    notifier(redis).attempted(PHONE);

    verify(notifications, timeout(5000).times(1)).sendEmail(any(), any(), anyMap());
  }

  @Test
  void noOwnerMeansNoEmailAndNoKey() {
    notifier(redis).notifyOwner("+910000000000");

    verify(notifications, never()).sendEmail(any(), any(), anyMap());
    assertThat(redis.keys("*")).isEmpty();
  }

  @Test
  void redisDownMeansNoThrowNoEmailAndOneThrottledWarning() {
    LettuceConnectionFactory dead = connectionFactory("127.0.0.1", 1);
    StringRedisTemplate template = new StringRedisTemplate(dead);
    template.afterPropertiesSet();
    PhoneClaimNotifier notifier = notifier(template);

    notifier.notifyOwner(PHONE);
    notifier.notifyOwner(PHONE);
    dead.destroy();

    verify(notifications, never()).sendEmail(any(), any(), anyMap());
    assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
  }

  private static LettuceConnectionFactory connectionFactory(String host, int port) {
    LettuceConnectionFactory f =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    f.afterPropertiesSet();
    return f;
  }
}
