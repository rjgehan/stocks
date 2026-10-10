package com.stocks.agents;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.contract.agents.dto.AgentRunStatus;
import com.stocks.contract.agents.dto.AgentRunTrigger;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code agent_run} and {@code agent_run_tool} (§20.6). Written only by {@link AgentRunService#report}. */
@Repository
public class AgentRunRepository {

  /** One {@code agent_run} row. */
  public record RunRow(long id, String publicId, long playerId, Long apiKeyId, String runRef, AgentRunTrigger trigger,
                       String slot, String model, AgentRunStatus status, Instant startedAt, Instant endedAt,
                       LocalDate tradeDate, long durationMs, int turns, int toolCalls, int toolErrors, int orders,
                       long tokensInput, long tokensCacheRead, long tokensCacheWrite, long tokensOutput,
                       BigDecimal costUsd, BigDecimal daySpentUsd, BigDecimal dailyCapUsd, String message,
                       String runnerVersion, Instant receivedAt) {}

  /** {@code calls} calls of {@code tool} that ended with {@code code}. */
  public record ToolCount(String tool, String code, int calls) {}

  /** One ET day of a bot: runs, the highest reported day spend and cap. */
  public record DayRow(LocalDate date, int runs, BigDecimal spentUsd, BigDecimal capUsd) {}

  private static final String COLUMNS = "id, public_id, player_id, api_key_id, run_ref, run_trigger, slot, model, "
      + "status, started_at, ended_at, trade_date, duration_ms, turns, tool_calls, tool_errors, orders_placed, "
      + "tokens_input, tokens_cache_read, tokens_cache_write, tokens_output, cost_usd, day_spent_usd, daily_cap_usd, "
      + "message, runner_version, received_at";

  private final JdbcClient jdbc;

  public AgentRunRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<RunRow> byRef(long playerId, String runRef) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM agent_run WHERE player_id = ? AND run_ref = ?")
        .params(playerId, runRef).query(AgentRunRepository::row).optional();
  }

  /** Inserts the run and its tool counts in one transaction; returns the row id. */
  @Transactional
  public long insert(RunRow r, List<ToolCount> tools) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO agent_run (public_id, player_id, api_key_id, run_ref, run_trigger, slot, model, status,
                                   started_at, ended_at, trade_date, duration_ms, turns, tool_calls, tool_errors,
                                   orders_placed, tokens_input, tokens_cache_read, tokens_cache_write, tokens_output,
                                   cost_usd, day_spent_usd, daily_cap_usd, message, runner_version, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(r.publicId(), r.playerId(), new SqlParameterValue(Types.BIGINT, r.apiKeyId()), r.runRef(),
            r.trigger().name(), new SqlParameterValue(Types.VARCHAR, r.slot()), r.model(), r.status().name(),
            Jdbc.ts(r.startedAt()), Jdbc.ts(r.endedAt()), r.tradeDate(), r.durationMs(), r.turns(), r.toolCalls(),
            r.toolErrors(), r.orders(), r.tokensInput(), r.tokensCacheRead(), r.tokensCacheWrite(), r.tokensOutput(),
            r.costUsd(), r.daySpentUsd(), r.dailyCapUsd(), new SqlParameterValue(Types.VARCHAR, r.message()),
            new SqlParameterValue(Types.VARCHAR, r.runnerVersion()), Jdbc.ts(r.receivedAt()))
        .update(keys, "id");
    long id = keys.getKeyAs(Long.class) != null ? keys.getKeyAs(Long.class)
        : jdbc.sql("SELECT id FROM agent_run WHERE public_id = ?").param(r.publicId()).query(Long.class).single();
    for (ToolCount t : tools) {
      jdbc.sql("INSERT INTO agent_run_tool (run_id, tool, result_code, calls) VALUES (?, ?, ?, ?)")
          .params(id, t.tool(), t.code(), t.calls()).update();
    }
    return id;
  }

  /** Newest first ({@code started_at DESC, id DESC}); {@code limit} rows at most. Null filters match everything. */
  public List<RunRow> page(Long playerIdOrNull, AgentRunStatus statusOrNull, Cursor cursor, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM agent_run WHERE 1 = 1");
    List<Object> params = new ArrayList<>();
    if (playerIdOrNull != null) {
      sql.append(" AND player_id = ?");
      params.add(playerIdOrNull);
    }
    if (statusOrNull != null) {
      sql.append(" AND status = ?");
      params.add(statusOrNull.name());
    }
    if (cursor != null) {
      sql.append(" AND (started_at < ? OR (started_at = ? AND id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY started_at DESC, id DESC LIMIT ").append(Math.max(1, limit));
    return jdbc.sql(sql.toString()).params(params).query(AgentRunRepository::row).list();
  }

  public Optional<RunRow> latest(long playerId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM agent_run WHERE player_id = ? ORDER BY started_at DESC, id DESC "
        + "LIMIT 1").param(playerId).query(AgentRunRepository::row).optional();
  }

  /** The latest run whose status is one of {@code statuses}. */
  public Optional<RunRow> latestWithStatus(long playerId, Collection<AgentRunStatus> statuses) {
    if (statuses.isEmpty()) {
      return Optional.empty();
    }
    String in = statuses.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
    return jdbc.sql("SELECT " + COLUMNS + " FROM agent_run WHERE player_id = ? AND status IN (" + in + ") "
        + "ORDER BY started_at DESC, id DESC LIMIT 1").param(playerId).query(AgentRunRepository::row).optional();
  }

  public int count(long playerId) {
    return jdbc.sql("SELECT COUNT(*) FROM agent_run WHERE player_id = ?").param(playerId)
        .query(Integer.class).single();
  }

  public int countSince(long playerId, Instant since) {
    return jdbc.sql("SELECT COUNT(*) FROM agent_run WHERE player_id = ? AND started_at >= ?")
        .params(playerId, Jdbc.ts(since)).query(Integer.class).single();
  }

  /** Per ET day in {@code [from, to]} with at least one run, oldest first. */
  public List<DayRow> days(long playerId, LocalDate from, LocalDate to) {
    return jdbc.sql("""
            SELECT trade_date, COUNT(*) AS runs, MAX(day_spent_usd) AS spent, MAX(daily_cap_usd) AS cap
              FROM agent_run WHERE player_id = ? AND trade_date >= ? AND trade_date <= ?
             GROUP BY trade_date ORDER BY trade_date""")
        .params(playerId, from, to)
        .query((rs, n) -> new DayRow(Jdbc.date(rs, "trade_date"), rs.getInt("runs"),
            rs.getBigDecimal("spent"), rs.getBigDecimal("cap")))
        .list();
  }

  /** Tool calls by tool and code of the runs of {@code playerId} on ET days {@code ≥ from}. */
  public List<ToolCount> toolTotals(long playerId, LocalDate from) {
    return jdbc.sql("""
            SELECT t.tool, t.result_code, SUM(t.calls) AS calls
              FROM agent_run_tool t JOIN agent_run r ON r.id = t.run_id
             WHERE r.player_id = ? AND r.trade_date >= ?
             GROUP BY t.tool, t.result_code ORDER BY t.tool, t.result_code""")
        .params(playerId, from)
        .query((rs, n) -> new ToolCount(rs.getString("tool"), rs.getString("result_code"), rs.getInt("calls")))
        .list();
  }

  /** The tool counts of each run, by run id, in tool/code order. */
  public Map<Long, List<ToolCount>> tools(Collection<Long> runIds) {
    Map<Long, List<ToolCount>> out = new LinkedHashMap<>();
    if (runIds.isEmpty()) {
      return out;
    }
    String in = runIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    jdbc.sql("SELECT run_id, tool, result_code, calls FROM agent_run_tool WHERE run_id IN (" + in + ") "
            + "ORDER BY run_id, tool, result_code")
        .query((rs, n) -> {
          out.computeIfAbsent(rs.getLong("run_id"), k -> new ArrayList<>())
              .add(new ToolCount(rs.getString("tool"), rs.getString("result_code"), rs.getInt("calls")));
          return null;
        })
        .list();
    return out;
  }

  private static RunRow row(ResultSet rs, int n) throws SQLException {
    return new RunRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"),
        Jdbc.longOrNull(rs, "api_key_id"), rs.getString("run_ref"), AgentRunTrigger.valueOf(rs.getString("run_trigger")),
        rs.getString("slot"), rs.getString("model"), AgentRunStatus.valueOf(rs.getString("status")),
        Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "ended_at"), Jdbc.date(rs, "trade_date"),
        rs.getLong("duration_ms"), rs.getInt("turns"), rs.getInt("tool_calls"), rs.getInt("tool_errors"),
        rs.getInt("orders_placed"), rs.getLong("tokens_input"), rs.getLong("tokens_cache_read"),
        rs.getLong("tokens_cache_write"), rs.getLong("tokens_output"), rs.getBigDecimal("cost_usd"),
        rs.getBigDecimal("day_spent_usd"), rs.getBigDecimal("daily_cap_usd"), rs.getString("message"),
        rs.getString("runner_version"), Jdbc.instant(rs, "received_at"));
  }
}
