package com.stocks.trading.fees;

import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.Side;
import com.stocks.common.jdbc.Jdbc;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The effective-dated {@code fee_schedule} (§8.1, §13), loaded once and kept in memory (rows change only through a
 * migration). {@link #reload()} re-reads the table.
 */
@Component
public class FeeSchedule {

  private final JdbcClient jdbc;
  private volatile List<FeeRow> rows;

  public FeeSchedule(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A fixed schedule (tests and tools). */
  public static FeeSchedule of(List<FeeRow> rows) {
    FeeSchedule s = new FeeSchedule(null);
    s.rows = List.copyOf(rows);
    return s;
  }

  public List<FeeRow> all() {
    List<FeeRow> r = rows;
    if (r == null) {
      r = reload();
    }
    return r;
  }

  /** Rows charging {@code side} on {@code tradeDate}, in table order (SEC, TAF, CAT). */
  public List<FeeRow> applicable(Side side, LocalDate tradeDate) {
    return all().stream().filter(r -> r.appliesTo(side, tradeDate)).toList();
  }

  public synchronized List<FeeRow> reload() {
    if (jdbc == null) {
      return rows;
    }
    rows = jdbc.sql("""
            SELECT fee_code, applies_to, basis, rate, rounding, max_per_order, effective_from, effective_to,
                   source_note
              FROM fee_schedule ORDER BY id""")
        .query((rs, n) -> new FeeRow(FeeCode.valueOf(rs.getString("fee_code")), rs.getString("applies_to"),
            rs.getString("basis"), rs.getBigDecimal("rate"), rs.getString("rounding"),
            rs.getBigDecimal("max_per_order"), Jdbc.date(rs, "effective_from"), Jdbc.date(rs, "effective_to"),
            rs.getString("source_note")))
        .list();
    return rows;
  }
}
