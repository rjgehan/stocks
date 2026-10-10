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
public record TestDb(DataSource dataSource, JdbcClient jdbc, String vendor) {

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
    config.setMinimumIdle(1);
    config.setPoolName("testdb-" + POOLS.incrementAndGet());
    return new HikariDataSource(config);
  }

  private static final java.util.concurrent.atomic.AtomicInteger POOLS = new java.util.concurrent.atomic.AtomicInteger();

  private static TestDb migrate(DataSource ds, String vendor) {
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/common", "classpath:db/migration/" + vendor)
        .outOfOrder(true)
        .load()
        .migrate();
    return new TestDb(ds, JdbcClient.create(ds), vendor);
  }

  public boolean isPostgres() {
    return POSTGRESQL.equals(vendor);
  }
}
