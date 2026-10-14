package com.stocks.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.jdbc.Jdbc;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Database contract (§8) that runs unchanged on H2 ({@link ApplicationH2IT}) and on embedded PostgreSQL 18.6
 * ({@link PostgresSchemaIT}, {@code -Ppg-it}): Flyway V1 + the vendor extras apply, the CHECK constraints reject bad
 * rows, and the {@link Jdbc} typing rules plus the cast-everything {@code MERGE} form work on both drivers.
 */
abstract class SchemaContract {

  /** Every table of §8.1 plus Spring Session's (§8.2/§8.3). */
  static final List<String> TABLES = List.of(
      "player", "api_key", "invite", "app_setting", "asset", "trading_day", "calendar_override", "daily_mark",
      "price_adjustment", "fee_schedule", "corporate_action", "account", "transfer_batch", "cash_transfer",
      "trade_order", "order_event", "execution", "dividend_entitlement", "cash_ledger", "position", "position_lot",
      "position_adjustment", "account_event", "account_violation", "equity_snapshot", "snapshot_holding",
      "intraday_equity", "benchmark_daily", "leaderboard_snapshot", "feed_event", "watchlist", "watchlist_item",
      "notification", "idempotency_record", "job_run", "audit_log", "spring_session", "spring_session_attributes",
      // §20.8 manual bots (V10)
      "manual_bot", "manual_session");

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final Instant T0 = Instant.parse("2026-10-09T14:00:00Z");

  protected abstract JdbcClient jdbc();

  /** Flyway's {@code {vendor}}: {@code h2} or {@code postgresql}. */
  protected abstract String vendor();

  // ------------------------------------------------------------------------------------------------ Flyway

  @Test
  void flywayAppliedV1AndTheVendorExtras() {
    List<Map<String, Object>> rows = jdbc()
        .sql("SELECT version, description, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")
        .query().listOfRows();
    assertThat(rows).extracting(r -> r.get("version")).containsSubsequence("1", "1.0.1");
    assertThat(rows).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
    String extras = vendor().equals("h2") ? "h2 extras" : "pg extras";
    assertThat(rows).extracting(r -> r.get("description")).contains("init", extras);
  }

  @Test
  void everyTableExists() {
    for (String table : TABLES) {
      Long count = jdbc().sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
      assertThat(count).as(table).isNotNull();
    }
  }

  @Test
  void feeScheduleIsSeeded() {
    List<String> codes = jdbc().sql("SELECT fee_code FROM fee_schedule ORDER BY id").query(String.class).list();
    assertThat(codes).containsExactly("SEC", "TAF", "TAF", "TAF", "CAT");
    BigDecimal sec = jdbc().sql("SELECT rate FROM fee_schedule WHERE fee_code = 'SEC'").query(BigDecimal.class).single();
    assertThat(sec).isEqualByComparingTo("0.0000206");
    assertThat(sec.scale()).isEqualTo(12);
  }

  // ------------------------------------------------------------------------------------------------ CHECKs

  @Test
  void anAiPlayerCanNeverBeAdmin() {
    long owner = insertHuman("PLAYER");
    insertAi(owner, "PLAYER"); // valid row first, so only the role differs
    assertThatThrownBy(() -> insertAi(owner, "ADMIN")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc().sql("""
            INSERT INTO player (public_id, handle, display_name, kind, created_at) VALUES (?, ?, 'No Password', 'HUMAN', ?)
            """).params(pid("ply"), handle(), Jdbc.ts(T0)).update())
        .isInstanceOf(DataIntegrityViolationException.class); // humans need a password hash
  }

  @Test
  void cashAccountsCannotGoNegative() {
    long player = insertHuman("PLAYER");
    insertAccount(player, 1, "CLOSED", "0.00");
    assertThatThrownBy(() -> insertAccount(player, 2, "CLOSED", "-0.01"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void fractionalOrdersMustBeDayMarketOrLimit() {
    long player = insertHuman("PLAYER");
    long account = insertAccount(player, 1, "ACTIVE", "500.00");
    String symbol = insertAsset();
    insertOrder(account, player, symbol, "MARKET", "DAY", true, "0.5");      // valid
    assertThatThrownBy(() -> insertOrder(account, player, symbol, "MARKET", "GTC", true, "0.5"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertOrder(account, player, symbol, "STOP", "DAY", true, "0.5"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ------------------------------------------------------------------------------------------------ JDBC typing

  @Test
  void instantsRoundTripThroughOffsetDateTimeAtMicrosecondPrecision() {
    String job = "TEST_TS_" + SEQ.incrementAndGet();
    Instant started = Instant.parse("2026-03-08T07:00:00.123456789Z"); // DST change day in New York
    jdbc().sql("INSERT INTO job_run (job_name, run_key, status, started_at, finished_at) VALUES (?, ?, ?, ?, ?)")
        .params(job, "2026-03-08", "DONE", Jdbc.ts(started), Jdbc.tsParam(null))
        .update();
    Map<String, Instant> read = jdbc().sql("SELECT started_at, finished_at FROM job_run WHERE job_name = ?")
        .param(job)
        .query((rs, n) -> {
          Map<String, Instant> m = new java.util.HashMap<>();
          m.put("started", Jdbc.instant(rs, "started_at"));
          m.put("finished", Jdbc.instant(rs, "finished_at"));
          return m;
        })
        .single();
    assertThat(read.get("started")).isEqualTo(Instant.parse("2026-03-08T07:00:00.123456Z"));
    assertThat(read.get("finished")).isNull();

    // comparisons on bound instants work server-side
    Long hits = jdbc().sql("SELECT COUNT(*) FROM job_run WHERE job_name = ? AND started_at >= ? AND started_at < ?")
        .params(job, Jdbc.ts(started.minusSeconds(1)), Jdbc.ts(started.plusSeconds(1)))
        .query(Long.class).single();
    assertThat(hits).isEqualTo(1L);
  }

  @Test
  void datesAndTypedNullsBind() {
    long player = insertHuman("PLAYER");
    long account = insertAccount(player, 1, "ACTIVE", "1.00");
    jdbc().sql("""
            INSERT INTO equity_snapshot (account_id, trade_date, as_of, cash, long_market_value, dividends_receivable,
              equity, net_contributions, units, unit_value, unit_series, flows_in_day, pnl_day, fills_day,
              marks_quality, computed_at)
            VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, 1, 0, 0, 0, 'FINAL', ?)
            """)
        .params(account, LocalDate.of(2026, 10, 13), Jdbc.ts(T0), new BigDecimal("1.00"), new BigDecimal("1.00"),
            new BigDecimal("1.00"), new BigDecimal("1.000000000000000000"), new BigDecimal("1.000000000000000000"),
            Jdbc.ts(T0))
        .update();
    record Row(LocalDate tradeDate, Instant asOf, BigDecimal unitValue) {}
    Row row = jdbc().sql("SELECT trade_date, as_of, unit_value FROM equity_snapshot WHERE account_id = ?")
        .param(account)
        .query((rs, n) -> new Row(Jdbc.date(rs, "trade_date"), Jdbc.instant(rs, "as_of"), rs.getBigDecimal("unit_value")))
        .single();
    assertThat(row.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(row.asOf()).isEqualTo(T0);
    assertThat(row.unitValue()).isEqualTo(new BigDecimal("1.000000000000000000"));

    // typed nulls in an UPDATE (Jdbc.nullOf / decimalParam / dateParam)
    int updated = jdbc().sql("UPDATE account SET restriction_until = ?, final_equity = ?, closed_at = ? WHERE id = ?")
        .params(Jdbc.tsParam(null), Jdbc.decimalParam(null), Jdbc.nullOf(Types.TIMESTAMP_WITH_TIMEZONE), account)
        .update();
    assertThat(updated).isEqualTo(1);
  }

  @Test
  void mergeWithExplicitCastsHandlesNullsOnBothDatabases() {
    String symbol = insertAsset();
    String merge = """
        MERGE INTO daily_mark d
        USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS DATE), CAST(? AS NUMERIC(19,4)), CAST(? AS NUMERIC(19,4)),
                       CAST(? AS VARCHAR(16)), CAST(? AS VARCHAR(12)), CAST(? AS TIMESTAMP WITH TIME ZONE)))
          AS v(symbol, trade_date, oc, dem, basis, quality, at)
        ON d.symbol = v.symbol AND d.trade_date = v.trade_date
        WHEN MATCHED THEN UPDATE SET official_close = COALESCE(v.oc, d.official_close),
          day_end_mark = COALESCE(v.dem, d.day_end_mark), day_end_basis = COALESCE(v.basis, d.day_end_basis),
          quality = v.quality, updated_at = v.at
        WHEN NOT MATCHED THEN INSERT (symbol, trade_date, official_close, day_end_mark, day_end_basis, quality, updated_at)
          VALUES (v.symbol, v.trade_date, v.oc, v.dem, v.basis, v.quality, v.at)
        """;
    LocalDate day = LocalDate.of(2026, 10, 9);
    // 16:20 job: official close only (day-end mark and basis are NULL)
    jdbc().sql(merge).params(symbol, day, new BigDecimal("227.4100"), null, null, "PROVISIONAL", Jdbc.ts(T0)).update();
    // 20:20 job: day-end mark, official close NULL (kept by COALESCE)
    jdbc().sql(merge)
        .params(symbol, day, null, new BigDecimal("227.9000"), "SIP_1MIN", "FINAL", Jdbc.ts(T0.plusSeconds(3600)))
        .update();

    record Mark(BigDecimal oc, BigDecimal dem, String basis, String quality, Instant at) {}
    Mark m = jdbc().sql("SELECT official_close, day_end_mark, day_end_basis, quality, updated_at FROM daily_mark WHERE symbol = ?")
        .param(symbol)
        .query((rs, n) -> new Mark(rs.getBigDecimal("official_close"), rs.getBigDecimal("day_end_mark"),
            rs.getString("day_end_basis"), rs.getString("quality"), Jdbc.instant(rs, "updated_at")))
        .single();
    assertThat(m).isEqualTo(new Mark(new BigDecimal("227.4100"), new BigDecimal("227.9000"), "SIP_1MIN", "FINAL",
        T0.plusSeconds(3600)));
  }

  // ------------------------------------------------------------------------------------------------ fixtures

  protected static String handle() {
    return "u" + SEQ.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36);
  }

  protected static String pid(String prefix) {
    String n = Integer.toString(SEQ.incrementAndGet());
    return prefix + "_" + "0".repeat(26 - n.length()) + n;
  }

  protected long insertHuman(String role) {
    String handle = handle();
    jdbc().sql("""
            INSERT INTO player (public_id, handle, display_name, kind, role, password_hash, created_at)
            VALUES (?, ?, 'Test Human', 'HUMAN', ?, '{bcrypt}x', ?)
            """)
        .params(pid("ply"), handle, role, Jdbc.ts(T0))
        .update();
    return jdbc().sql("SELECT id FROM player WHERE handle = ?").param(handle).query(Long.class).single();
  }

  protected long insertAi(long owner, String role) {
    String handle = handle();
    jdbc().sql("""
            INSERT INTO player (public_id, handle, display_name, kind, role, ai_model, owner_player_id, created_at)
            VALUES (?, ?, 'Test Bot', 'AI', ?, 'Claude', ?, ?)
            """)
        .params(pid("ply"), handle, role, owner, Jdbc.ts(T0))
        .update();
    return jdbc().sql("SELECT id FROM player WHERE handle = ?").param(handle).query(Long.class).single();
  }

  protected long insertAccount(long player, int runNo, String status, String cash) {
    String publicId = pid("acc");
    jdbc().sql("""
            INSERT INTO account (public_id, player_id, run_no, status, cash_balance, starting_cash, opened_at)
            VALUES (?, ?, ?, ?, ?, 500.00, ?)
            """)
        .params(publicId, player, runNo, status, new BigDecimal(cash), Jdbc.ts(T0))
        .update();
    return jdbc().sql("SELECT id FROM account WHERE public_id = ?").param(publicId).query(Long.class).single();
  }

  protected String insertAsset() {
    String symbol = "T" + SEQ.incrementAndGet();
    jdbc().sql("""
            INSERT INTO asset (symbol, name, tradable, fractionable, status, source, updated_at)
            VALUES (?, 'Test Corp', TRUE, TRUE, 'ACTIVE', 'simulated', ?)
            """)
        .params(symbol, Jdbc.ts(T0))
        .update();
    return symbol;
  }

  protected void insertOrder(long account, long player, String symbol, String type, String tif, boolean fractional,
                             String qty) {
    jdbc().sql("""
            INSERT INTO trade_order (public_id, account_id, player_id, client_order_id, request_hash, symbol, side,
              order_type, qty, fractional, stop_price, time_in_force, extended_hours, status, session_date,
              received_at, accepted_at, eligible_at, expires_at, channel)
            VALUES (?, ?, ?, ?, ?, ?, 'BUY', ?, ?, ?, ?, ?, FALSE, 'PENDING', ?, ?, ?, ?, ?, 'WEB')
            """)
        .params(pid("ord"), account, player, "c-" + SEQ.incrementAndGet(), "0".repeat(64), symbol, type,
            new BigDecimal(qty), fractional, type.startsWith("STOP") ? new BigDecimal("1.00") : null, tif,
            LocalDate.of(2026, 10, 9), Jdbc.ts(T0), Jdbc.ts(T0), Jdbc.ts(T0), Jdbc.ts(T0.plusSeconds(7200)))
        .update();
  }
}
