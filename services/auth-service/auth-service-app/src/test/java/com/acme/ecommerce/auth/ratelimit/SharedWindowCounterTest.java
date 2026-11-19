package com.acme.ecommerce.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.acme.ecommerce.auth.support.RedisTestContainer;
import com.acme.ecommerce.auth.support.TestAuthProperties;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** AC-2: Redis trouble of every kind degrades to a per-instance limit, never to an error. */
@ExtendWith(OutputCaptureExtension.class)
class SharedWindowCounterTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int CAPACITY = 3;

  private final FixedTimeProvider time = FixedTimeProvider.atEpoch();
  private final List<LettuceConnectionFactory> factories = new ArrayList<>();

  @AfterEach
  void tearDown() {
    factories.forEach(LettuceConnectionFactory::destroy);
  }

  private StringRedisTemplate template(String host, int port) {
    Duration timeout = Duration.ofMillis(250);
    LettuceClientConfiguration client =
        LettuceClientConfiguration.builder()
            .commandTimeout(timeout)
            .clientOptions(
                ClientOptions.builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                    .build())
            .build();
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
    factory.afterPropertiesSet();
    factories.add(factory);
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  private SharedWindowCounter counter(StringRedisTemplate template) {
    return new SharedWindowCounter(
        template, time, TestAuthProperties.with(TestAuthProperties.rateLimit(CAPACITY, 1000)));
  }

  private void assertFallbackTripsAtCapacityPlusOne(SharedWindowCounter counter, String key) {
    for (int i = 1; i <= CAPACITY; i++) {
      long count = counter.increment(key, WINDOW);
      assertThat(count).isEqualTo(i).isLessThanOrEqualTo(CAPACITY);
    }
    assertThat(counter.increment(key, WINDOW)).isEqualTo(CAPACITY + 1).isGreaterThan(CAPACITY);
  }

  @Test
  @DisplayName("closed port: no exception, per-instance limit trips at C+1, WARN logged")
  void closedPort(CapturedOutput output) throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    SharedWindowCounter counter = counter(template("127.0.0.1", port));

    assertThatCode(() -> counter.increment("closed", WINDOW)).doesNotThrowAnyException();
    assertFallbackTripsAtCapacityPlusOne(counter, "closed2");
    assertThat(output).contains(SharedWindowCounter.WARN_MESSAGE);
  }

  @Test
  @DisplayName("Lua script error (WRONGTYPE key): no exception, fallback trips at C+1, WARN logged")
  void scriptError(CapturedOutput output) {
    StringRedisTemplate template = template(RedisTestContainer.host(), RedisTestContainer.port());
    String key = "lua-error";
    String fullKey = key + ":" + (time.epochMilli() / WINDOW.toMillis());
    template.delete(fullKey);
    template.opsForHash().put(fullKey, "field", "value");
    SharedWindowCounter counter = counter(template);

    assertFallbackTripsAtCapacityPlusOne(counter, key);
    assertThat(output).contains(SharedWindowCounter.WARN_MESSAGE);
    template.delete(fullKey);
  }

  @Test
  @DisplayName("250 ms timeout: a server that accepts but never answers fails open quickly")
  void timeout(CapturedOutput output) throws Exception {
    try (ServerSocket silent = new ServerSocket(0)) {
      Thread acceptor =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket ignored = silent.accept()) {
                      Thread.sleep(10_000);
                    } catch (Exception e) {
                      // closed by the test
                    }
                  });
      SharedWindowCounter counter = counter(template("127.0.0.1", silent.getLocalPort()));

      long started = System.nanoTime();
      assertFallbackTripsAtCapacityPlusOne(counter, "timeout");
      Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

      assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
      assertThat(output).contains(SharedWindowCounter.WARN_MESSAGE);
      acceptor.interrupt();
    }
  }

  @Test
  @DisplayName("50 concurrent increments give exactly 1..50 (atomic) and the key has a TTL")
  void concurrentIncrementsAreAtomic() throws Exception {
    StringRedisTemplate template = template(RedisTestContainer.host(), RedisTestContainer.port());
    SharedWindowCounter counter = counter(template);
    String key = "concurrent-" + System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<Long>> futures = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        Callable<Long> task = () -> counter.increment(key, WINDOW);
        futures.add(pool.submit(task));
      }
      List<Long> counts = new ArrayList<>();
      for (Future<Long> future : futures) {
        counts.add(future.get());
      }
      assertThat(counts).doesNotHaveDuplicates().hasSize(50).contains(1L, 50L);
      assertThat(counts).allMatch(c -> c >= 1 && c <= 50);
      String fullKey = key + ":" + (time.epochMilli() / WINDOW.toMillis());
      assertThat(template.opsForValue().get(fullKey)).isEqualTo("50");
      assertThat(template.getExpire(fullKey)).isBetween(1L, 90L);
    } finally {
      pool.shutdownNow();
    }
  }
}
