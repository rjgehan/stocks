package com.stocks.common.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * JDBC typing rules (§8, CONTRACT). <b>JDBC never sees a {@link Instant}</b>: pgjdbc 42.7.13 rejects it in both
 * directions ("Can't infer the SQL type to use for an instance of java.time.Instant" on bind, "conversion to class
 * java.time.Instant from timestamptz not supported" on read) while H2 accepts it, so the bug would only show in
 * production. Bind {@link #ts(Instant)} and read {@link #instant(ResultSet, String)} instead.
 *
 * <p>{@code ResultSet.getObject(…, Class)}, {@code ResultSet.getTimestamp} and {@code java.sql.Timestamp} are allowed
 * only in this package (ArchUnit rule 9). {@code LocalDate} and {@code BigDecimal} bind directly on both drivers.
 *
 * <p>A null whose SQL type matters (every parameter in a {@code MERGE … USING (VALUES …)} list, §8) is bound through
 * an explicit {@code CAST(? AS …)} in the SQL, or through the typed helpers here ({@link #tsParam}, {@link #nullOf}).
 *
 * <pre>{@code
 * jdbc.sql("INSERT INTO job_run (job_name, run_key, status, started_at) VALUES (?, ?, ?, ?)")
 *     .params(name, runKey, "RUNNING", Jdbc.ts(time.now())).update();
 * jdbc.sql("SELECT started_at FROM job_run WHERE job_name = ?").param(name)
 *     .query((rs, n) -> Jdbc.instant(rs, "started_at")).optional();
 * }</pre>
 */
public final class Jdbc {

  private Jdbc() {}

  /**
   * The value to bind for an instant: an {@link OffsetDateTime} at UTC, truncated to microseconds (the precision of
   * {@code TIMESTAMP WITH TIME ZONE} on both databases). Null-safe.
   */
  public static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }

  /** {@link #ts(Instant)} typed as {@code TIMESTAMP_WITH_TIMEZONE}, so a null still carries its SQL type. */
  public static SqlParameterValue tsParam(Instant instant) {
    return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, ts(instant));
  }

  /** A typed SQL null, e.g. {@code Jdbc.nullOf(Types.NUMERIC)}. */
  public static SqlParameterValue nullOf(int sqlType) {
    return new SqlParameterValue(sqlType, null);
  }

  /** A nullable {@code DATE} parameter that keeps its type when null. */
  public static SqlParameterValue dateParam(LocalDate date) {
    return new SqlParameterValue(Types.DATE, date);
  }

  /** A nullable {@code NUMERIC} parameter that keeps its type when null. */
  public static SqlParameterValue decimalParam(BigDecimal value) {
    return new SqlParameterValue(Types.NUMERIC, value);
  }

  /** Reads a {@code TIMESTAMP WITH TIME ZONE} column; {@code null} for SQL NULL. */
  public static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** Reads a {@code TIMESTAMP WITH TIME ZONE} column by 1-based index; {@code null} for SQL NULL. */
  public static Instant instant(ResultSet rs, int columnIndex) throws SQLException {
    OffsetDateTime value = rs.getObject(columnIndex, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** Reads a {@code DATE} column; {@code null} for SQL NULL. */
  public static LocalDate date(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, LocalDate.class);
  }

  /** Reads a {@code DATE} column by 1-based index; {@code null} for SQL NULL. */
  public static LocalDate date(ResultSet rs, int columnIndex) throws SQLException {
    return rs.getObject(columnIndex, LocalDate.class);
  }

  /** Reads a nullable {@code BIGINT} column. */
  public static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }

  /** Reads a nullable {@code INT} column. */
  public static Integer intOrNull(ResultSet rs, String column) throws SQLException {
    int v = rs.getInt(column);
    return rs.wasNull() ? null : v;
  }
}
