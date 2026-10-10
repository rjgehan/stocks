package com.stocks.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.config.SingleInstanceGuard;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The suite's database switch (§23.2, §26): a plain Spring test context runs on in-memory H2, and under
 * {@code -Ppg-it} on its own fresh database of the JVM's embedded PostgreSQL 18.6 (V1 + pg extras applied by Boot's
 * Flyway). Also boots with {@code stocks.single-instance.enabled=true}: the guard takes the H2 lease or the
 * per-database advisory lock without colliding with other test contexts.
 */
@SpringBootTest(properties = "stocks.single-instance.enabled=true")
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class EmbeddedPgContextIT {

  @Autowired DataSource dataSource;
  @Autowired JdbcClient jdbc;
  @Autowired Environment environment;
  @Autowired SingleInstanceGuard guard;

  @Test
  void runsOnTheDatabaseTheBuildAsksFor() throws Exception {
    try (var c = dataSource.getConnection()) {
      String product = c.getMetaData().getDatabaseProductName();
      if (EmbeddedPg.enabled()) {
        assertThat(product).isEqualTo("PostgreSQL");
        assertThat(c.getMetaData().getDatabaseProductVersion()).startsWith("18.6");
        assertThat(c.getCatalog()).startsWith("stocks_").isEqualTo(environment.getProperty("stocks.test.pg.database"));
        assertThat(jdbc.sql("SELECT script FROM flyway_schema_history WHERE version = '1.0.1'").query(String.class)
            .single()).isEqualTo("V1_0_1__pg_extras.sql");
        assertThat(guard.mode()).isEqualTo("postgresql-advisory-lock");
      } else {
        assertThat(product).isEqualTo("H2");
        assertThat(guard.mode()).isEqualTo("h2-lease");
        assertThat(jdbc.sql("SELECT setting_value FROM app_setting WHERE setting_key = 'engine.lease'")
            .query(String.class).single()).startsWith(guard.identity().instanceId() + "|");
      }
    }
    assertThat(guard.isHeld()).isTrue();
  }

  @Test
  void h2OnlyClassesAndStarH2itClassesKeepH2() throws Exception {
    @H2Only class Annotated {}
    assertThat(EmbeddedPgContextCustomizerFactory.keepsH2(Annotated.class)).isTrue();
    assertThat(EmbeddedPgContextCustomizerFactory.keepsH2(Class.forName("com.stocks.db.ApplicationH2IT"))).isTrue();
    assertThat(EmbeddedPgContextCustomizerFactory.keepsH2(EmbeddedPgContextIT.class)).isFalse();
  }
}
