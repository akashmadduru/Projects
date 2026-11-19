package com.acme.ecommerce.auth.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** One Redis per JVM (same image as deploy/local/docker-compose.yml); Ryuk removes it at exit. */
public final class RedisTestContainer {

  @SuppressWarnings("resource")
  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  private RedisTestContainer() {}

  public static String host() {
    return REDIS.getHost();
  }

  public static int port() {
    return REDIS.getMappedPort(6379);
  }
}
