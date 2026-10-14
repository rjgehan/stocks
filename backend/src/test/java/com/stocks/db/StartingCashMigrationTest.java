package com.stocks.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.testkit.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V11 on a database that already has its settings (production): {@code league.defaultStartingCash} becomes
 * 500000.00 and the other settings stay. Embedded PostgreSQL under {@code -Ppg-it}, H2 otherwise.
 */
class StartingCashMigrationTest {

  @Test
  void v11RaisesTheStoredDefaultStartingCash() throws Exception {
    boolean pg = EmbeddedPg.enabled();
    String url = pg ? EmbeddedPg.jdbcUrl(EmbeddedPg.createDatabase())
        : "jdbc:h2:mem:v11-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
            + "DB_CLOSE_DELAY=-1";
    // one connection for the whole test: H2 2.4.240 invalidates CHECKs when their creating connection closes (§28 #24)
    try (Connection c = pg ? DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        : DriverManager.getConnection(url, "sa", "")) {
      SingleConnectionDataSource ds = new SingleConnectionDataSource(c, true);
      String[] locations = {"classpath:db/migration/common", "classpath:db/migration/" + (pg ? "postgresql" : "h2")};
      Flyway.configure().dataSource(ds).locations(locations).target("8").load().migrate();   // before V11
      JdbcClient jdbc = JdbcClient.create(ds);
      Instant seeded = Instant.parse("2026-10-09T14:00:00Z");
      for (String[] kv : new String[][] {{"league.defaultStartingCash", "500.00"},
          {"league.allowSelfReset", "false"}}) {
        jdbc.sql("INSERT INTO app_setting (setting_key, setting_value, updated_by, updated_at) VALUES (?, ?, NULL, ?)")
            .params(kv[0], kv[1], Jdbc.ts(seeded)).update();
      }

      Flyway.configure().dataSource(ds).locations(locations).outOfOrder(false).load().migrate();

      assertThat(jdbc.sql("SELECT setting_value FROM app_setting WHERE setting_key = 'league.defaultStartingCash'")
          .query(String.class).single()).isEqualTo("500000.00");
      assertThat(jdbc.sql("SELECT updated_at FROM app_setting WHERE setting_key = 'league.defaultStartingCash'")
          .query((rs, n) -> Jdbc.instant(rs, "updated_at")).single()).isAfter(seeded);
      assertThat(jdbc.sql("SELECT setting_value FROM app_setting WHERE setting_key = 'league.allowSelfReset'")
          .query(String.class).single()).isEqualTo("false");
    }
  }
}
