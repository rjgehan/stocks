package com.stocks.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.jdbc.Jdbc;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The same {@link SchemaContract}, plus the PostgreSQL-only guards of §8.2, on a Docker-free embedded PostgreSQL 18.6
 * (zonky binaries 18.6.0). Runs only with {@code ./mvnw verify -Ppg-it} ({@code stocks.test.pg=true}). The whole
 * application boots against it, so Boot's Flyway resolves {@code {vendor}} to {@code postgresql}.
 *
 * <p>Phase 0 starts its own server; the WS1 test kit's {@code EmbeddedPgContextInitializer} (§26: one server per JVM,
 * a fresh database per Spring context) will replace {@link Pg} when it lands.
 */
@EnabledIfSystemProperty(named = "stocks.test.pg", matches = "true")
@SpringBootTest
@ActiveProfiles("test")
class PostgresSchemaIT extends SchemaContract {

  /** One embedded server for this JVM, started on first use and stopped at JVM exit. */
  static final class Pg {
    private static EmbeddedPostgres server;

    static synchronized EmbeddedPostgres get() {
      if (server == null) {
        try {
          server = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
          throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
            server.close();
          } catch (IOException ignored) {
            // JVM is exiting
          }
        }, "embedded-pg-stop"));
      }
      return server;
    }
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    EmbeddedPostgres pg = Pg.get();
    registry.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  @Autowired JdbcClient jdbcClient;
  @Autowired DataSource dataSource;

  @Override
  protected JdbcClient jdbc() {
    return jdbcClient;
  }

  @Override
  protected String vendor() {
    return "postgresql";
  }

  @Test
  void runsOnPostgres18_6() throws Exception {
    try (var c = dataSource.getConnection()) {
      assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(c.getMetaData().getDatabaseProductVersion()).startsWith("18.6");
    }
  }

  @Test
  void appendOnlyTablesRejectUpdateAndDelete() {
    jdbcClient.sql("INSERT INTO audit_log (occurred_at, category, action, outcome, request_id) VALUES (?, 'SYSTEM', 'TEST', 'OK', ?)")
        .params(Jdbc.ts(Instant.parse("2026-10-09T14:00:00Z")), "pg-it")
        .update();
    assertThatThrownBy(() -> jdbcClient.sql("UPDATE audit_log SET action = 'X' WHERE request_id = 'pg-it'").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only table: audit_log");
    assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM audit_log WHERE request_id = 'pg-it'").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only table: audit_log");
    Long triggers = jdbcClient.sql("SELECT COUNT(*) FROM pg_trigger WHERE tgname LIKE 'trg\\_%\\_ro' AND NOT tgisinternal")
        .query(Long.class).single();
    assertThat(triggers).isEqualTo(8L);
  }

  @Test
  void onePlayerHasAtMostOneActiveAccount() {
    long player = insertHuman("PLAYER");
    insertAccount(player, 1, "CLOSED", "0.00");
    insertAccount(player, 2, "ACTIVE", "500.00");
    assertThatThrownBy(() -> insertAccount(player, 3, "ACTIVE", "500.00"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_account_one_active");
  }
}
