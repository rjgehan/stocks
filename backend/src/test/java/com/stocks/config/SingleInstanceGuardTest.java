package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.testkit.EmbeddedPg;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * §12.9 single-instance guard, on its own fresh database per test (so tests never collide with each other or with a
 * running dev app): the H2 lease (live lease refused, stale lease and dead-PID lease taken over, released on
 * shutdown) and, under {@code -Ppg-it}, the PostgreSQL advisory lock.
 */
class SingleInstanceGuardTest {

  static final Duration HEARTBEAT = Duration.ofHours(1); // no background renewals during a test
  static final Duration STALE = Duration.ofSeconds(30);

  static SingleInstanceGuard guard(TestDb db, MutableTimeSource wall, String id, String host, long pid) {
    return new SingleInstanceGuard(db.dataSource(), wall, new SingleInstanceGuard.Identity(id, host, pid), HEARTBEAT,
        STALE);
  }

  static String lease(TestDb db) {
    return db.jdbc().sql("SELECT setting_value FROM app_setting WHERE setting_key = 'engine.lease'")
        .query(String.class).optional().orElse(null);
  }

  static void plantLease(TestDb db, String value, Instant at) {
    db.jdbc().sql("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('engine.lease', ?, ?)")
        .params(value, Jdbc.ts(at)).update();
  }

  @Test
  void h2LeaseRefusesASecondLiveInstanceAndIsReleasedOnShutdown() {
    TestDb db = TestDb.h2();
    MutableTimeSource wall = TestTimes.clock();
    long me = ProcessHandle.current().pid();
    SingleInstanceGuard first = guard(db, wall, "first", "host-a", me);
    first.acquire();
    assertThat(first.isHeld()).isTrue();
    assertThat(first.mode()).isEqualTo("h2-lease");
    assertThat(lease(db)).startsWith("first|host-a|" + me + "|");

    SingleInstanceGuard second = guard(db, wall, "second", "host-a", me); // same host, live PID
    assertThatThrownBy(second::acquire).isInstanceOf(SingleInstanceException.class)
        .hasMessageContaining("Another instance holds the H2 lease");
    SingleInstanceGuard remote = guard(db, wall, "third", "host-b", 4242);
    assertThatThrownBy(remote::acquire).isInstanceOf(SingleInstanceException.class);

    first.release();
    assertThat(lease(db)).isNull();
    second.acquire();
    assertThat(lease(db)).startsWith("second|");
    second.release();
  }

  @Test
  void h2StaleLeaseIsTakenOver() {
    TestDb db = TestDb.h2();
    MutableTimeSource wall = TestTimes.clock();
    plantLease(db, "old|host-b|4242|" + wall.now().minusSeconds(31).toEpochMilli(), wall.now());
    SingleInstanceGuard g = guard(db, wall, "new", "host-a", ProcessHandle.current().pid());
    g.acquire();
    assertThat(lease(db)).startsWith("new|host-a|");
    g.release();
  }

  @Test
  void h2LeaseOfADeadPidOnThisHostIsTakenOver() {
    TestDb db = TestDb.h2();
    MutableTimeSource wall = TestTimes.clock();
    long deadPid = 999_999_999L;
    assertThat(ProcessHandle.of(deadPid)).isEmpty();
    plantLease(db, "crashed|host-a|" + deadPid + "|" + wall.now().toEpochMilli(), wall.now());
    SingleInstanceGuard g = guard(db, wall, "restarted", "host-a", ProcessHandle.current().pid());
    g.acquire();
    assertThat(lease(db)).startsWith("restarted|");
    g.release();

    plantLease(db, "crashed|host-b|" + deadPid + "|" + wall.now().toEpochMilli(), wall.now());
    SingleInstanceGuard other = guard(db, wall, "restarted2", "host-a", ProcessHandle.current().pid());
    assertThatThrownBy(other::acquire).as("a dead PID on ANOTHER host proves nothing")
        .isInstanceOf(SingleInstanceException.class);
  }

  @Test
  void leaseValuesRoundTrip() {
    SingleInstanceGuard.Lease l = new SingleInstanceGuard.Lease("id", "host", 12, Instant.ofEpochMilli(1234));
    assertThat(SingleInstanceGuard.Lease.parse(l.format())).contains(l);
    assertThat(SingleInstanceGuard.Lease.parse("garbage")).isEmpty();
    assertThat(SingleInstanceGuard.Identity.current().pid()).isEqualTo(ProcessHandle.current().pid());
  }

  @Test
  @EnabledIfSystemProperty(named = "stocks.test.pg", matches = "true")
  void postgresAdvisoryLockAllowsOneInstancePerDatabase() {
    TestDb db = TestDb.postgres();
    MutableTimeSource wall = TestTimes.clock();
    SingleInstanceGuard first = guard(db, wall, "pg-1", "host-a", 1);
    first.acquire();
    assertThat(first.mode()).isEqualTo("postgresql-advisory-lock");
    SingleInstanceGuard second = guard(db, wall, "pg-2", "host-a", 2);
    assertThatThrownBy(second::acquire).isInstanceOf(SingleInstanceException.class)
        .hasMessageContaining("advisory lock 727474");
    TestDb otherDb = TestDb.postgres();
    SingleInstanceGuard elsewhere = guard(otherDb, wall, "pg-3", "host-a", 3);
    elsewhere.acquire(); // advisory locks are per database: another test database never collides
    elsewhere.release();
    first.release();
    second.acquire();
    assertThat(second.isHeld()).isTrue();
    second.release();
    assertThat(EmbeddedPg.enabled()).isTrue();
  }
}
