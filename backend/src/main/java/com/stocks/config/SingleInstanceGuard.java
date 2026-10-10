package com.stocks.config;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.SystemTimeSource;
import com.stocks.common.time.TimeSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One app instance per database (§12.9, D1), enabled by {@code stocks.single-instance.enabled} (default true; the
 * {@code test} profile turns it off, so test contexts never collide, and each test context has its own database
 * anyway).
 * <ul>
 *   <li><b>PostgreSQL</b>: {@code pg_try_advisory_lock(727474)} on a dedicated connection held for the life of the
 *       app. The lock dies with the connection, so a crashed instance never blocks a restart. Advisory locks are
 *       per database.</li>
 *   <li><b>H2</b>: a lease row {@code app_setting['engine.lease'] = <instanceId>|<host>|<pid>|<heartbeat millis>},
 *       renewed every 10 s. Startup is refused if another instance renewed it within the last 30 s, unless the
 *       lease names this host and its PID no longer exists (a kill -9 or IDE hard stop), in which case it is taken
 *       over. Graceful shutdown deletes the row. The dev URL uses {@code AUTO_SERVER=TRUE}, so H2's file lock alone
 *       would not stop a second process.</li>
 * </ul>
 * Lease times are <b>wall-clock</b> ({@link SystemTimeSource}), not the app's {@code TimeSource}: SIM offset clocks
 * must not make a live lease look stale. On failure the guard throws {@link SingleInstanceException} from
 * {@link #afterSingletonsInstantiated()}, so context startup fails; it never calls {@code System.exit} (in Surefire
 * only that test context fails).
 */
@Component
@ConditionalOnBooleanProperty(name = "stocks.single-instance.enabled", matchIfMissing = true)
public class SingleInstanceGuard implements SmartInitializingSingleton, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

  public static final long ADVISORY_LOCK_KEY = 727474L;
  public static final String LEASE_KEY = "engine.lease";
  static final Duration HEARTBEAT = Duration.ofSeconds(10);
  static final Duration STALE_AFTER = Duration.ofSeconds(30);

  /** Who this instance is: a random id plus host and PID (for the dead-PID takeover). */
  public record Identity(String instanceId, String host, long pid) {
    public static Identity current() {
      String name = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host", no DNS lookup
      int at = name.indexOf('@');
      String host = at >= 0 ? name.substring(at + 1) : "unknown";
      return new Identity(UUID.randomUUID().toString(), sanitize(host), ProcessHandle.current().pid());
    }
  }

  /** A parsed lease value. */
  record Lease(String instanceId, String host, long pid, Instant heartbeat) {
    String format() {
      return instanceId + "|" + host + "|" + pid + "|" + heartbeat.toEpochMilli();
    }

    static Optional<Lease> parse(String value) {
      if (value == null) {
        return Optional.empty();
      }
      String[] parts = value.split("\\|");
      if (parts.length != 4) {
        return Optional.empty();
      }
      try {
        return Optional.of(new Lease(parts[0], parts[1], Long.parseLong(parts[2]),
            Instant.ofEpochMilli(Long.parseLong(parts[3]))));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
  }

  private final DataSource dataSource;
  private final JdbcClient jdbc;
  private final TimeSource wallClock;
  private final Identity identity;
  private final Duration heartbeat;
  private final Duration staleAfter;

  private ScheduledExecutorService heartbeats;
  private Connection lockConnection;   // PG
  private String leaseValue;           // H2: the value we last wrote
  private volatile boolean held;
  private String mode;

  @Autowired
  public SingleInstanceGuard(DataSource dataSource) {
    this(dataSource, new SystemTimeSource(), Identity.current(), HEARTBEAT, STALE_AFTER);
  }

  SingleInstanceGuard(DataSource dataSource, TimeSource wallClock, Identity identity, Duration heartbeat,
                      Duration staleAfter) {
    this.dataSource = dataSource;
    this.jdbc = JdbcClient.create(dataSource);
    this.wallClock = wallClock;
    this.identity = identity;
    this.heartbeat = heartbeat;
    this.staleAfter = staleAfter;
  }

  @Override
  public void afterSingletonsInstantiated() {
    acquire();
  }

  @Override
  public void destroy() {
    release();
  }

  /** True while this instance holds the lock or lease. */
  public boolean isHeld() {
    return held;
  }

  /** {@code postgresql-advisory-lock} or {@code h2-lease} once acquired. */
  public String mode() {
    return mode;
  }

  public Identity identity() {
    return identity;
  }

  public synchronized void acquire() {
    if (held) {
      return;
    }
    String product;
    try (Connection c = dataSource.getConnection()) {
      product = c.getMetaData().getDatabaseProductName();
    } catch (SQLException e) {
      throw new SingleInstanceException("Single-instance guard: cannot reach the database", e);
    }
    if ("PostgreSQL".equalsIgnoreCase(product)) {
      mode = "postgresql-advisory-lock";
      acquireAdvisoryLock();
    } else {
      mode = "h2-lease";
      acquireLease();
    }
    held = true;
    heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "single-instance-heartbeat");
      t.setDaemon(true);
      return t;
    });
    heartbeats.scheduleWithFixedDelay(this::beat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    log.info("Single-instance guard: {} held by {}@{} (pid {})", mode, identity.instanceId(), identity.host(),
        identity.pid());
  }

  public synchronized void release() {
    if (heartbeats != null) {
      heartbeats.shutdownNow();
      heartbeats = null;
    }
    if (!held) {
      return;
    }
    held = false;
    if (lockConnection != null) {
      Connection c = lockConnection;
      lockConnection = null;
      try (c; PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
        ps.setLong(1, ADVISORY_LOCK_KEY);
        ps.execute();
      } catch (SQLException e) {
        log.debug("Single-instance guard: unlock failed (the lock dies with the connection)", e);
      }
    }
    if (leaseValue != null) {
      try {
        jdbc.sql("DELETE FROM app_setting WHERE setting_key = ? AND setting_value = ?")
            .params(LEASE_KEY, leaseValue).update();
      } catch (RuntimeException e) {
        log.warn("Single-instance guard: could not delete the H2 lease: {}", e.toString());
      }
      leaseValue = null;
    }
  }

  // ------------------------------------------------------------------------------------------- PostgreSQL

  private void acquireAdvisoryLock() {
    Connection c = null;
    try {
      c = dataSource.getConnection();
      c.setAutoCommit(true);
      if (!tryLock(c)) {
        c.close();
        throw new SingleInstanceException("Another instance holds the PostgreSQL advisory lock " + ADVISORY_LOCK_KEY
            + " on this database (§12.9). Stop it first.");
      }
      lockConnection = c;
    } catch (SQLException e) {
      closeQuietly(c);
      throw new SingleInstanceException("Single-instance guard: advisory lock failed", e);
    }
  }

  private static boolean tryLock(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      ps.setLong(1, ADVISORY_LOCK_KEY);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  // ------------------------------------------------------------------------------------------- H2 lease

  private void acquireLease() {
    Instant now = wallClock.now();
    Lease mine = new Lease(identity.instanceId(), identity.host(), identity.pid(), now);
    Optional<String> current = readLease();
    if (current.isEmpty()) {
      try {
        jdbc.sql("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES (?, ?, ?)")
            .params(LEASE_KEY, mine.format(), Jdbc.ts(now)).update();
        leaseValue = mine.format();
        return;
      } catch (DuplicateKeyException race) {
        current = readLease();
      }
    }
    String existing = current.orElse(null);
    Optional<Lease> other = Lease.parse(existing);
    String why;
    if (other.isEmpty()) {
      why = "unreadable lease '" + existing + "'";
    } else if (other.get().instanceId().equals(identity.instanceId())) {
      why = "our own lease";
    } else if (other.get().heartbeat().isBefore(now.minus(staleAfter))) {
      why = "stale lease (last heartbeat " + other.get().heartbeat() + ")";
    } else if (other.get().host().equals(identity.host()) && ProcessHandle.of(other.get().pid()).isEmpty()) {
      why = "lease of dead PID " + other.get().pid() + " on this host";
    } else {
      throw new SingleInstanceException("Another instance holds the H2 lease (" + other.get().host() + ", pid "
          + other.get().pid() + ", heartbeat " + other.get().heartbeat() + "); stop it first (§12.9).");
    }
    int updated = jdbc.sql("UPDATE app_setting SET setting_value = ?, updated_at = ? "
            + "WHERE setting_key = ? AND setting_value = ?")
        .params(mine.format(), Jdbc.ts(now), LEASE_KEY, existing).update();
    if (updated != 1) {
      throw new SingleInstanceException("Another instance took the H2 lease while this one was starting.");
    }
    leaseValue = mine.format();
    log.warn("Single-instance guard: took over the H2 lease ({})", why);
  }

  private Optional<String> readLease() {
    return jdbc.sql("SELECT setting_value FROM app_setting WHERE setting_key = ?").param(LEASE_KEY)
        .query(String.class).optional();
  }

  // ------------------------------------------------------------------------------------------- heartbeat

  private synchronized void beat() {
    if (!held) {
      return;
    }
    try {
      if (lockConnection != null) {
        if (!lockConnection.isValid(5)) {
          log.error("Single-instance guard: the advisory-lock connection died; re-acquiring");
          closeQuietly(lockConnection);
          lockConnection = null;
          acquireAdvisoryLock();
        }
      } else if (leaseValue != null) {
        Lease renewed = new Lease(identity.instanceId(), identity.host(), identity.pid(), wallClock.now());
        int n = jdbc.sql("UPDATE app_setting SET setting_value = ?, updated_at = ? "
                + "WHERE setting_key = ? AND setting_value = ?")
            .params(renewed.format(), Jdbc.ts(renewed.heartbeat()), LEASE_KEY, leaseValue).update();
        if (n == 1) {
          leaseValue = renewed.format();
        } else {
          log.error("Single-instance guard: the H2 lease was taken by another instance; re-acquiring");
          leaseValue = null;
          acquireLease();
        }
      }
    } catch (RuntimeException | SQLException e) {
      log.error("Single-instance guard heartbeat failed: {}", e.toString());
    }
  }

  private static void closeQuietly(Connection c) {
    if (c != null) {
      try {
        c.close();
      } catch (SQLException ignored) {
        // nothing to do
      }
    }
  }

  private static String sanitize(String host) {
    return host.replace('|', '_');
  }
}
