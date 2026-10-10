package com.stocks.testkit;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One Docker-free embedded PostgreSQL 18.6 per JVM (zonky binaries 18.6.0, §26), started on first use and stopped at
 * JVM exit. Every caller gets its own database ({@link #createDatabase()}), so Spring test contexts and component
 * tests never share data, engines or advisory locks. Used when {@code -Ppg-it} sets {@code stocks.test.pg=true}.
 */
public final class EmbeddedPg {

  public static final String USER = "postgres";
  public static final String PASSWORD = "postgres";

  private static EmbeddedPostgres server;

  private EmbeddedPg() {}

  /** True under {@code ./mvnw verify -Ppg-it}. */
  public static boolean enabled() {
    return Boolean.getBoolean("stocks.test.pg");
  }

  public static synchronized EmbeddedPostgres server() {
    if (server == null) {
      try {
        server = EmbeddedPostgres.builder().start();
      } catch (IOException e) {
        throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
      }
      EmbeddedPostgres started = server;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          started.close();
        } catch (IOException ignored) {
          // the JVM is exiting
        }
      }, "embedded-pg-stop"));
    }
    return server;
  }

  /** Creates an empty database {@code stocks_<uuid>} and returns its name. */
  public static String createDatabase() {
    String name = "stocks_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = server().getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("CREATE DATABASE " + name + " failed", e);
    }
    return name;
  }

  public static String jdbcUrl(String database) {
    return server().getJdbcUrl(USER, database);
  }

  /** A plain (non-pooled) DataSource for one database. */
  public static DataSource dataSource(String database) {
    DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl(database), USER, PASSWORD);
    ds.setDriverClassName("org.postgresql.Driver");
    return ds;
  }
}
