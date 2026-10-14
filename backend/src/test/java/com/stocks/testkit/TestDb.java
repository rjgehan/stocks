package com.stocks.testkit;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A fresh, fully migrated database for component tests that do not need a Spring context (repositories, guards,
 * job tables). {@link #fresh()} follows the suite: embedded PostgreSQL 18.6 under {@code -Ppg-it}, in-memory H2
 * ({@code MODE=PostgreSQL}, §8) otherwise; {@link #h2()} and {@link #postgres()} pick one explicitly. Each call is a new,
 * empty database with V1 and the vendor extras applied, exactly as Boot's Flyway does it.
 *
 * <pre>{@code
 * TestDb db = TestDb.fresh();
 * new JobRunGuard(db.jdbc(), TestTimes.clock()) ...
 * }</pre>
 */
public record TestDb(DataSource dataSource, JdbcClient jdbc, String vendor) implements AutoCloseable {

  public static final String H2 = "h2";
  public static final String POSTGRESQL = "postgresql";

  public static TestDb fresh() {
    return EmbeddedPg.enabled() ? postgres() : h2();
  }

  public static TestDb h2() {
    return migrate(pool("jdbc:h2:mem:testdb-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", ""), H2);
  }

  public static TestDb postgres() {
    return migrate(pool(EmbeddedPg.jdbcUrl(EmbeddedPg.createDatabase()), EmbeddedPg.USER, EmbeddedPg.PASSWORD),
        POSTGRESQL);
  }

  /**
   * A fresh database of the suite's vendor migrated only up to {@code targetVersion} (e.g. {@code "8.1"}), for data
   * migration tests; {@link #migrateToLatest()} applies the rest.
   */
  public static TestDb migratedTo(String targetVersion) {
    boolean pg = EmbeddedPg.enabled();
    DataSource ds = pg ? pool(EmbeddedPg.jdbcUrl(EmbeddedPg.createDatabase()), EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        : pool("jdbc:h2:mem:testdb-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
    String vendor = pg ? POSTGRESQL : H2;
    flyway(ds, vendor).target(targetVersion).load().migrate();
    return new TestDb(ds, JdbcClient.create(ds), vendor);
  }

  /** Applies every pending migration. */
  public void migrateToLatest() {
    flyway(dataSource, vendor).load().migrate();
  }

  /**
   * A small Hikari pool, like the app's. (H2 evaluates CHECK constraints through the session that created them, so
   * the connection that ran the migration must stay open: a non-pooled DataSource fails with "Check constraint
   * invalid … database has been closed".)
   */
  private static HikariDataSource pool(String url, String user, String password) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(10);
    // H2 needs the migrating connection kept (see above); on PostgreSQL an idle pool must not pin a server slot
    // forever, since most tests never close their TestDb
    boolean h2 = url.startsWith("jdbc:h2:");
    config.setMinimumIdle(h2 ? 1 : 0);
    // H2: never retire a connection (0 = no idle timeout, no max lifetime). With two connections open, Hikari's
    // housekeeper (every 30 s) could retire the migrating one as the surplus idle connection, and a test that runs
    // longer than that on a loaded machine then fails with "Check constraint invalid … database has been closed".
    config.setIdleTimeout(h2 ? 0 : 10_000);
    if (h2) {
      config.setMaxLifetime(0);
    }
    config.setPoolName("testdb-" + POOLS.incrementAndGet());
    return new HikariDataSource(config);
  }

  private static final java.util.concurrent.atomic.AtomicInteger POOLS = new java.util.concurrent.atomic.AtomicInteger();

  private static TestDb migrate(DataSource ds, String vendor) {
    flyway(ds, vendor).load().migrate();
    return new TestDb(ds, JdbcClient.create(ds), vendor);
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DataSource ds, String vendor) {
    return Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/common", "classpath:db/migration/" + vendor)
        .outOfOrder(true);
  }

  public boolean isPostgres() {
    return POSTGRESQL.equals(vendor);
  }

  /** Closes the pool (the database itself stays until the JVM's embedded server stops). */
  @Override
  public void close() {
    if (dataSource instanceof HikariDataSource h) {
      h.close();
    }
  }
}
