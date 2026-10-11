package com.stocks.marketdata.core;

import com.stocks.common.jdbc.Jdbc;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code screener_stat} (§8.5, §15.10): per-symbol screener statistics. Two cast {@code MERGE}s (§8): the volume
 * averages (a whole-universe batch) and one company profile; each leaves the other half of the row alone. Every
 * nullable parameter carries its SQL type, so a null binds on PostgreSQL too ({@code -Ppg-it}).
 */
public class ScreenerStatRepository {

  /** One stored row. Every member but {@code symbol} and {@code updatedAt} may be null. */
  public record Row(String symbol, Long avgVolume, Long avgVolumeIex, Integer avgVolumeDays, LocalDate avgVolumeAsOf,
                    Instant volumeAt, BigDecimal sharesOutstanding, BigDecimal marketCap, String industry,
                    String profileSource, Instant profileAt, Instant updatedAt) {}

  /** The averages of one symbol: SIP and IEX bases (either may be null), the number of days and the last day. */
  public record Volumes(Long avgVolume, Long avgVolumeIex, Integer days, LocalDate asOf) {}

  /** A company profile answer; every value may be null (an empty answer is stored too, with its source and time). */
  public record Profile(BigDecimal sharesOutstanding, BigDecimal marketCap, String industry, String source) {}

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  public ScreenerStatRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(dataSource);
    this.template = new JdbcTemplate(dataSource);
  }

  public List<Row> loadAll() {
    return jdbc.sql("""
            SELECT symbol, avg_volume, avg_volume_iex, avg_volume_days, avg_volume_as_of, volume_at,
                   shares_outstanding, market_cap, industry, profile_source, profile_at, updated_at
            FROM screener_stat""")
        .query(ScreenerStatRepository::map).list();
  }

  /** Sets the volume averages of every symbol in {@code volumes} (the profile columns are kept). */
  public void upsertVolumes(Map<String, Volumes> volumes, Instant now) {
    if (volumes.isEmpty()) {
      return;
    }
    String sql = """
        MERGE INTO screener_stat s
        USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS INTEGER),
                       CAST(? AS DATE), CAST(? AS TIMESTAMP WITH TIME ZONE)))
              AS v(symbol, avg_volume, avg_volume_iex, avg_volume_days, avg_volume_as_of, at)
        ON s.symbol = v.symbol
        WHEN MATCHED THEN UPDATE SET avg_volume = v.avg_volume, avg_volume_iex = v.avg_volume_iex,
             avg_volume_days = v.avg_volume_days, avg_volume_as_of = v.avg_volume_as_of, volume_at = v.at,
             updated_at = v.at
        WHEN NOT MATCHED THEN INSERT (symbol, avg_volume, avg_volume_iex, avg_volume_days, avg_volume_as_of, volume_at,
             updated_at)
             VALUES (v.symbol, v.avg_volume, v.avg_volume_iex, v.avg_volume_days, v.avg_volume_as_of, v.at, v.at)""";
    List<Object[]> args = new ArrayList<>(volumes.size());
    volumes.forEach((symbol, v) -> args.add(new Object[] {symbol,
        new SqlParameterValue(Types.BIGINT, v.avgVolume()), new SqlParameterValue(Types.BIGINT, v.avgVolumeIex()),
        new SqlParameterValue(Types.INTEGER, v.days()), Jdbc.dateParam(v.asOf()), Jdbc.tsParam(now)}));
    for (int i = 0; i < args.size(); i += 500) {
      template.batchUpdate(sql, args.subList(i, Math.min(args.size(), i + 500)));
    }
  }

  /** Sets one symbol's profile columns (the volume columns are kept); {@code now} is also the attempt time. */
  public void upsertProfile(String symbol, Profile p, Instant now) {
    jdbc.sql("""
            MERGE INTO screener_stat s
            USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS NUMERIC(20,0)), CAST(? AS NUMERIC(22,0)),
                           CAST(? AS VARCHAR(100)), CAST(? AS VARCHAR(12)), CAST(? AS TIMESTAMP WITH TIME ZONE)))
                  AS v(symbol, shares_outstanding, market_cap, industry, profile_source, at)
            ON s.symbol = v.symbol
            WHEN MATCHED THEN UPDATE SET shares_outstanding = v.shares_outstanding, market_cap = v.market_cap,
                 industry = v.industry, profile_source = v.profile_source, profile_at = v.at, updated_at = v.at
            WHEN NOT MATCHED THEN INSERT (symbol, shares_outstanding, market_cap, industry, profile_source, profile_at,
                 updated_at)
                 VALUES (v.symbol, v.shares_outstanding, v.market_cap, v.industry, v.profile_source, v.at, v.at)""")
        .params(symbol, Jdbc.decimalParam(positive(p.sharesOutstanding())), Jdbc.decimalParam(positive(p.marketCap())),
            new SqlParameterValue(Types.VARCHAR, clip(p.industry(), 100)),
            new SqlParameterValue(Types.VARCHAR, clip(p.source(), 12)), Jdbc.tsParam(now))
        .update();
  }

  /** A whole number ≥ 1, or null (the CHECK refuses zero shares; a zero cap means "unknown"). */
  private static BigDecimal positive(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal r = v.setScale(0, java.math.RoundingMode.HALF_EVEN);
    return r.signum() > 0 ? r : null;
  }

  private static String clip(String s, int max) {
    if (s == null || s.isBlank()) {
      return null;
    }
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  private static Row map(ResultSet rs, int n) throws SQLException {
    return new Row(rs.getString("symbol"), Jdbc.longOrNull(rs, "avg_volume"), Jdbc.longOrNull(rs, "avg_volume_iex"),
        Jdbc.intOrNull(rs, "avg_volume_days"), Jdbc.date(rs, "avg_volume_as_of"), Jdbc.instant(rs, "volume_at"),
        rs.getBigDecimal("shares_outstanding"), rs.getBigDecimal("market_cap"), rs.getString("industry"),
        rs.getString("profile_source"), Jdbc.instant(rs, "profile_at"), Jdbc.instant(rs, "updated_at"));
  }
}
