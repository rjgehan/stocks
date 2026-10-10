package com.stocks.trading.engine;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.trading.InvariantResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The ledger and position invariants I1–I9 of §8.4, checked over every account. Runs at boot, as
 * {@code CheckInvariantsCmd} (hourly and on resume) and after every engine test. A failure halts the engine in
 * production (§12.9). At most {@link #MAX_FAILURES} failures are reported.
 */
@Component
public class InvariantChecker {

  static final int MAX_FAILURES = 50;

  private final JdbcClient jdbc;
  private final TimeSource time;

  public InvariantChecker(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  public InvariantResult check() {
    List<String> f = new ArrayList<>();
    i1(f);
    i2(f);
    i3i4(f);
    i5(f);
    i6i7(f);
    i8(f);
    i9(f);
    List<String> failures = f.size() > MAX_FAILURES ? f.subList(0, MAX_FAILURES) : f;
    return new InvariantResult(time.now(), f.isEmpty(), List.copyOf(failures));
  }

  /** I1: the latest ledger row's balance_after = Σ amount. */
  private void i1(List<String> f) {
    jdbc.sql("""
            SELECT l.account_id, l.balance_after, s.total FROM cash_ledger l
              JOIN (SELECT account_id, MAX(id) AS max_id, SUM(amount) AS total FROM cash_ledger GROUP BY account_id) s
                ON s.max_id = l.id
             WHERE l.balance_after <> s.total""")
        .query((rs, n) -> "I1 account " + rs.getLong(1) + ": latest balance_after " + rs.getBigDecimal(2)
            + " <> ledger sum " + rs.getBigDecimal(3))
        .list().forEach(f::add);
  }

  /** I2: account.cash_balance = Σ ledger, and ≥ 0 for CASH accounts. */
  private void i2(List<String> f) {
    jdbc.sql("""
            SELECT a.id, a.cash_balance, COALESCE(s.total, 0) FROM account a
              LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM cash_ledger GROUP BY account_id) s
                ON s.account_id = a.id
             WHERE a.cash_balance <> COALESCE(s.total, 0) OR (a.account_type = 'CASH' AND a.cash_balance < 0)""")
        .query((rs, n) -> "I2 account " + rs.getLong(1) + ": cash_balance " + rs.getBigDecimal(2) + " <> ledger "
            + rs.getBigDecimal(3) + " (or negative)")
        .list().forEach(f::add);
  }

  /** I3 net contributions and I4 units against the applied transfers. */
  private void i3i4(List<String> f) {
    jdbc.sql("""
            SELECT a.id, a.net_contributions, COALESCE(s.nc, 0), a.units, COALESCE(s.u, 0) FROM account a
              LEFT JOIN (SELECT account_id,
                                SUM(CASE WHEN status = 'APPLIED' AND is_contribution THEN amount ELSE 0 END) AS nc,
                                SUM(units_delta) AS u
                           FROM cash_transfer GROUP BY account_id) s ON s.account_id = a.id
             WHERE a.net_contributions <> COALESCE(s.nc, 0) OR a.units <> COALESCE(s.u, 0) OR a.units < 0""")
        .query((rs, n) -> "I3/I4 account " + rs.getLong(1) + ": net_contributions " + rs.getBigDecimal(2) + " vs "
            + rs.getBigDecimal(3) + ", units " + rs.getBigDecimal(4) + " vs " + rs.getBigDecimal(5))
        .list().forEach(f::add);
  }

  /** I5: position = Σ open lots (qty and cost) = Σ executions ± adjustments (renames mapped). */
  private void i5(List<String> f) {
    Map<String, BigDecimal[]> positions = new HashMap<>();
    jdbc.sql("SELECT account_id, symbol, qty, cost_basis FROM position").query((rs, n) -> {
      positions.put(rs.getLong(1) + "/" + rs.getString(2), new BigDecimal[] {rs.getBigDecimal(3), rs.getBigDecimal(4)});
      return null;
    }).list();
    Map<String, BigDecimal[]> lots = new HashMap<>();
    jdbc.sql("""
            SELECT account_id, symbol, SUM(qty_remaining), SUM(cost_remaining) FROM position_lot
             WHERE closed_at IS NULL GROUP BY account_id, symbol""")
        .query((rs, n) -> {
          lots.put(rs.getLong(1) + "/" + rs.getString(2), new BigDecimal[] {rs.getBigDecimal(3), rs.getBigDecimal(4)});
          return null;
        }).list();
    Map<String, BigDecimal> flows = new HashMap<>();
    jdbc.sql("""
            SELECT account_id, symbol, SUM(q) FROM (
                SELECT account_id, symbol, CASE WHEN side = 'BUY' THEN qty ELSE -qty END AS q FROM execution
                UNION ALL SELECT account_id, symbol_before, -qty_before FROM position_adjustment
                UNION ALL SELECT account_id, symbol_after, qty_after FROM position_adjustment) t
             GROUP BY account_id, symbol""")
        .query((rs, n) -> {
          flows.put(rs.getLong(1) + "/" + rs.getString(2), rs.getBigDecimal(3));
          return null;
        }).list();
    Set<String> keys = new HashSet<>(positions.keySet());
    keys.addAll(lots.keySet());
    keys.addAll(flows.keySet());
    for (String k : keys) {
      BigDecimal[] p = positions.getOrDefault(k, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      BigDecimal[] l = lots.getOrDefault(k, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      BigDecimal x = flows.getOrDefault(k, BigDecimal.ZERO);
      if (p[0].compareTo(l[0]) != 0 || p[1].compareTo(l[1]) != 0 || p[0].compareTo(x) != 0) {
        f.add("I5 " + k + ": position " + p[0] + "/" + p[1] + ", lots " + l[0] + "/" + l[1] + ", executions " + x);
      }
    }
  }

  /** I6 reserved shares ≤ position; I7 reserved cash ≤ cash. */
  private void i6i7(List<String> f) {
    jdbc.sql("""
            SELECT o.account_id, o.symbol, SUM(o.reserved_qty), COALESCE(MAX(p.qty), 0) FROM trade_order o
              LEFT JOIN position p ON p.account_id = o.account_id AND p.symbol = o.symbol
             WHERE o.side = 'SELL' AND o.status IN ('PENDING','WORKING','PARTIALLY_FILLED')
             GROUP BY o.account_id, o.symbol
            HAVING SUM(o.reserved_qty) > COALESCE(MAX(p.qty), 0)""")
        .query((rs, n) -> "I6 account " + rs.getLong(1) + " " + rs.getString(2) + ": reserved " + rs.getBigDecimal(3)
            + " > position " + rs.getBigDecimal(4))
        .list().forEach(f::add);
    jdbc.sql("""
            SELECT a.id, s.held, a.cash_balance FROM account a
              JOIN (SELECT account_id, SUM(reserved_cash) AS held FROM trade_order
                     WHERE side = 'BUY' AND status IN ('PENDING','WORKING','PARTIALLY_FILLED')
                     GROUP BY account_id) s ON s.account_id = a.id
             WHERE s.held > a.cash_balance""")
        .query((rs, n) -> "I7 account " + rs.getLong(1) + ": holds " + rs.getBigDecimal(2) + " > cash "
            + rs.getBigDecimal(3))
        .list().forEach(f::add);
  }

  /** I8: filled_qty and fees_total = Σ executions; terminal orders hold nothing. */
  private void i8(List<String> f) {
    jdbc.sql("""
            SELECT o.id, o.filled_qty, COALESCE(s.q, 0), o.fees_total, COALESCE(s.fees, 0) FROM trade_order o
              LEFT JOIN (SELECT order_id, SUM(qty) AS q, SUM(fee_sec + fee_taf + fee_cat) AS fees FROM execution
                          GROUP BY order_id) s ON s.order_id = o.id
             WHERE o.filled_qty <> COALESCE(s.q, 0) OR o.fees_total <> COALESCE(s.fees, 0)
                OR (o.status NOT IN ('PENDING','WORKING','PARTIALLY_FILLED')
                    AND (o.reserved_cash <> 0 OR o.reserved_qty <> 0))""")
        .query((rs, n) -> "I8 order " + rs.getLong(1) + ": filled " + rs.getBigDecimal(2) + " vs " + rs.getBigDecimal(3)
            + ", fees " + rs.getBigDecimal(4) + " vs " + rs.getBigDecimal(5))
        .list().forEach(f::add);
  }

  /** I9: one ledger row per execution (amount = net) and per applied transfer (amount = transfer amount). */
  private void i9(List<String> f) {
    jdbc.sql("""
            SELECT e.id, e.net_amount, COUNT(l.id), MIN(l.amount) FROM execution e
              LEFT JOIN cash_ledger l ON l.execution_id = e.id
             GROUP BY e.id, e.net_amount
            HAVING COUNT(l.id) <> 1 OR MIN(l.amount) <> e.net_amount""")
        .query((rs, n) -> "I9 execution " + rs.getLong(1) + ": " + rs.getLong(3) + " ledger rows")
        .list().forEach(f::add);
    jdbc.sql("""
            SELECT t.id, t.status, COUNT(l.id), MIN(l.amount), t.amount FROM cash_transfer t
              LEFT JOIN cash_ledger l ON l.transfer_id = t.id
             GROUP BY t.id, t.status, t.amount
            HAVING (t.status = 'APPLIED' AND (COUNT(l.id) <> 1 OR MIN(l.amount) <> t.amount))
                OR (t.status <> 'APPLIED' AND COUNT(l.id) <> 0)""")
        .query((rs, n) -> "I9 transfer " + rs.getLong(1) + " (" + rs.getString(2) + "): " + rs.getLong(3)
            + " ledger rows")
        .list().forEach(f::add);
  }
}
