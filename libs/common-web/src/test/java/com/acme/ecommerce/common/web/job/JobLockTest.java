package com.acme.ecommerce.common.web.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The cluster-wide job guard: exactly one replica runs a job, and a lock outage never skips it. */
class JobLockTest {

  private static final String LOCK_SQL = "select pg_try_advisory_lock(?)";
  private static final String UNLOCK_SQL = "select pg_advisory_unlock(?)";

  private ObjectProvider<DataSource> provider;
  private DataSource dataSource;
  private Connection connection;
  private PreparedStatement lockStatement;
  private PreparedStatement unlockStatement;
  private ResultSet lockResult;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() throws SQLException {
    provider = mock(ObjectProvider.class);
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    lockStatement = mock(PreparedStatement.class);
    unlockStatement = mock(PreparedStatement.class);
    lockResult = mock(ResultSet.class);

    when(provider.getIfAvailable()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(LOCK_SQL)).thenReturn(lockStatement);
    when(connection.prepareStatement(UNLOCK_SQL)).thenReturn(unlockStatement);
    when(lockStatement.executeQuery()).thenReturn(lockResult);
    when(lockResult.next()).thenReturn(true);
  }

  @Test
  @DisplayName("runs the job when it wins the lock, then releases the lock and the connection")
  void runsAndReleases() throws SQLException {
    when(lockResult.getBoolean(1)).thenReturn(true);
    AtomicInteger runs = new AtomicInteger();

    boolean ran = new JobLock(provider).runExclusive("job", runs::incrementAndGet);

    assertThat(ran).isTrue();
    assertThat(runs).hasValue(1);
    verify(unlockStatement).execute();
    verify(connection).close();
  }

  @Test
  @DisplayName("skips the job when another replica holds the lock")
  void skipsWhenLockIsHeld() throws SQLException {
    when(lockResult.getBoolean(1)).thenReturn(false);
    AtomicInteger runs = new AtomicInteger();

    boolean ran = new JobLock(provider).runExclusive("job", runs::incrementAndGet);

    assertThat(ran).isFalse();
    assertThat(runs).hasValue(0);
    verify(unlockStatement, never()).execute();
    verify(connection).close();
  }

  @Test
  @DisplayName("releases the lock even when the job throws")
  void releasesWhenJobFails() throws SQLException {
    when(lockResult.getBoolean(1)).thenReturn(true);

    try {
      new JobLock(provider)
          .runExclusive(
              "job",
              () -> {
                throw new IllegalStateException("boom");
              });
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessage("boom");
    }

    verify(unlockStatement).execute();
    verify(connection).close();
  }

  @Test
  @DisplayName("a lock outage never skips the job: it runs without the lock")
  void runsWhenLockCannotBeAttempted() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("database unreachable"));
    AtomicInteger runs = new AtomicInteger();

    boolean ran = new JobLock(provider).runExclusive("job", runs::incrementAndGet);

    assertThat(ran).isTrue();
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("runs the job when there is no DataSource at all")
  void runsWithoutDataSource() {
    when(provider.getIfAvailable()).thenReturn(null);
    AtomicInteger runs = new AtomicInteger();

    boolean ran = new JobLock(provider).runExclusive("job", runs::incrementAndGet);

    assertThat(ran).isTrue();
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("a failed unlock discards the connection so a held lock cannot return to the pool")
  void discardsConnectionWhenUnlockFails() throws SQLException {
    when(lockResult.getBoolean(1)).thenReturn(true);
    when(unlockStatement.execute()).thenThrow(new SQLException("connection lost"));

    new JobLock(provider).runExclusive("job", () -> {});

    verify(connection).abort(any());
  }

  @Test
  @DisplayName("the lock key is stable per name and different between names")
  void keyIsStable() {
    assertThat(JobLock.keyFor("a")).isEqualTo(JobLock.keyFor("a"));
    assertThat(JobLock.keyFor("a")).isNotEqualTo(JobLock.keyFor("b"));
  }
}
