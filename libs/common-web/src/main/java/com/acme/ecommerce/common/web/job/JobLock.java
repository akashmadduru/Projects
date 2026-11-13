package com.acme.ecommerce.common.web.job;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Cluster-wide mutual exclusion for {@code @Scheduled} jobs, backed by a PostgreSQL session-level
 * advisory lock.
 *
 * <p>WHY. Every service runs several replicas on Kubernetes, and a plain {@code @Scheduled} method
 * runs on every one of them. Jobs that are not idempotent (ad budget resets, subscription orders,
 * membership expiry mails, payment outcome reports) would then execute N times. This guard lets
 * exactly one replica run a given job at a time.
 *
 * <p>WHY AN ADVISORY LOCK AND NOT A LOCK TABLE. It needs no schema, no migration and no extra
 * dependency, and it cannot get stuck: the lock belongs to the database session, so if the pod
 * running the job dies, Postgres releases it the moment the connection drops.
 *
 * <p>SEMANTICS. A replica that does not win the lock simply skips this tick. If the lock cannot be
 * attempted at all (database unreachable, non-Postgres database) the job runs anyway, which is the
 * exact behaviour these jobs had before this guard existed; a lock outage therefore never causes a
 * missed run.
 */
public final class JobLock {

  private static final Logger log = LoggerFactory.getLogger(JobLock.class);

  private final ObjectProvider<DataSource> dataSource;

  public JobLock(ObjectProvider<DataSource> dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Runs {@code job} only if no other replica currently holds the lock called {@code name}.
   *
   * @return {@code true} if the job ran here, {@code false} if another replica holds the lock
   */
  public boolean runExclusive(String name, Runnable job) {
    DataSource ds = dataSource.getIfAvailable();
    if (ds == null) {
      job.run();
      return true;
    }
    long key = keyFor(name);
    Connection connection;
    try {
      connection = ds.getConnection();
    } catch (SQLException e) {
      log.warn("Job lock '{}' unavailable ({}); running without it", name, e.getMessage());
      job.run();
      return true;
    }
    boolean locked;
    try {
      locked = tryLock(connection, key);
    } catch (SQLException e) {
      closeQuietly(connection);
      log.warn("Job lock '{}' could not be attempted ({}); running without it", name, e.getMessage());
      job.run();
      return true;
    }
    if (!locked) {
      closeQuietly(connection);
      log.debug("Job '{}' is running on another replica; skipping this tick", name);
      return false;
    }
    try {
      job.run();
      return true;
    } finally {
      release(connection, key, name);
    }
  }

  private static boolean tryLock(Connection connection, long key) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
      ps.setLong(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private static void release(Connection connection, long key, String name) {
    try (PreparedStatement ps = connection.prepareStatement("select pg_advisory_unlock(?)")) {
      ps.setLong(1, key);
      ps.execute();
      closeQuietly(connection);
    } catch (SQLException | RuntimeException e) {
      // An unlock that failed must not return this session to the pool still holding the lock,
      // or the job would never run again until the connection is recycled. Abort it instead.
      log.warn("Job lock '{}' could not be released cleanly ({}); discarding connection", name, e.getMessage());
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException ignored) {
        closeQuietly(connection);
      }
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException | RuntimeException ignored) {
      // nothing useful to do
    }
  }

  /** Stable 64-bit key derived from the lock name (first 8 bytes of SHA-256). */
  static long keyFor(String name) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(("ecommerce-job:" + name).getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
