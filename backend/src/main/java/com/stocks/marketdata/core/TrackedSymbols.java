package com.stocks.marketdata.core;

import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The symbols the daily jobs and the {@code HOLDING} leases care about (§15.5): every symbol held by an account
 * ({@code position}, WS3) and every watched symbol ({@code watchlist_item}, WS4). Read-only, committed state; a
 * failure (a table that is not there yet) yields an empty set.
 */
public class TrackedSymbols {

  private static final Logger log = LoggerFactory.getLogger(TrackedSymbols.class);

  private final JdbcClient jdbc;

  public TrackedSymbols(DataSource dataSource) {
    this.jdbc = JdbcClient.create(dataSource);
  }

  public Set<String> held() {
    return query("SELECT DISTINCT symbol FROM position");
  }

  public Set<String> watched() {
    return query("SELECT DISTINCT symbol FROM watchlist_item");
  }

  private Set<String> query(String sql) {
    try {
      return new TreeSet<>(jdbc.sql(sql).query(String.class).list());
    } catch (RuntimeException e) {
      log.debug("tracked symbols ({}) unavailable: {}", sql, e.toString());
      return Set.of();
    }
  }
}
