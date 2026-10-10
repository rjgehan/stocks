package com.stocks.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit checks of the §8 JDBC typing helpers. The same helpers run against real H2 and PostgreSQL 18.6 in
 * {@code com.stocks.db.SchemaContract} (round trips, typed nulls, MERGE with casts).
 */
class JdbcTest {

  @Test
  void tsBindsAnOffsetDateTimeAtUtcTruncatedToMicros() {
    OffsetDateTime ts = Jdbc.ts(Instant.parse("2026-10-09T14:00:00.123456789Z"));
    assertThat(ts.getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(ts.toInstant()).isEqualTo(Instant.parse("2026-10-09T14:00:00.123456Z"));
    assertThat(Jdbc.ts(null)).isNull();
  }

  @Test
  void typedParametersKeepTheirSqlTypeWhenNull() {
    assertThat(Jdbc.tsParam(null).getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    assertThat(Jdbc.tsParam(null).getValue()).isNull();
    assertThat(Jdbc.tsParam(Instant.EPOCH).getValue()).isEqualTo(OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    assertThat(Jdbc.decimalParam(null).getSqlType()).isEqualTo(Types.NUMERIC);
    assertThat(Jdbc.decimalParam(BigDecimal.TEN).getValue()).isEqualTo(BigDecimal.TEN);
    assertThat(Jdbc.dateParam(null).getSqlType()).isEqualTo(Types.DATE);
    assertThat(Jdbc.nullOf(Types.BIGINT).getSqlType()).isEqualTo(Types.BIGINT);
  }

  @Test
  void readsGoThroughOffsetDateTimeAndLocalDateNeverInstant() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2026-10-09T10:00:00.5-04:00"));
    when(rs.getObject("missing", OffsetDateTime.class)).thenReturn(null);
    when(rs.getObject(3, OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-09T14:00:00Z"));
    when(rs.getObject("d", LocalDate.class)).thenReturn(LocalDate.of(2026, 10, 13));
    when(rs.getLong("n")).thenReturn(0L);
    when(rs.wasNull()).thenReturn(true);

    assertThat(Jdbc.instant(rs, "at")).isEqualTo(Instant.parse("2026-10-09T14:00:00.5Z"));
    assertThat(Jdbc.instant(rs, "missing")).isNull();
    assertThat(Jdbc.instant(rs, 3)).isEqualTo(Instant.parse("2026-10-09T14:00:00Z"));
    assertThat(Jdbc.date(rs, "d")).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(Jdbc.longOrNull(rs, "n")).isNull();
  }
}
