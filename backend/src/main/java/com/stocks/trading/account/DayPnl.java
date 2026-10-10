package com.stocks.trading.account;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Day P&L computed from trading's own tables, used only while WS4's {@code PerformanceQueries} is a Day-0 stub. Same
 * definitions as §16.2, with the previous close standing in for the previous EOD mark:
 * <pre>
 * position: MV_now(s) − qty_prev(s) × prevClose(s) + Σ net_amount of executions in s since the cut-off
 * account:  E_now − E_prev − Σ contribution flows since the cut-off;  return = U_now / U_prev − 1
 * </pre>
 * The cut-off is the previous trading day's {@code post_close_at} (00:00 ET today when the calendar is unavailable).
 */
@Component
public class DayPnl {

  /** Account day P&L and return (fraction, scale 10). */
  public record Account(BigDecimal dayPnl, BigDecimal dayReturn) {}

  private final JdbcClient jdbc;
  private final MarketDataService marketData;
  private final TradingClock clock;

  public DayPnl(JdbcClient jdbc, MarketDataService marketData, TradingClock clock) {
    this.jdbc = jdbc;
    this.marketData = marketData;
    this.clock = clock;
  }

  /** The start of the current P&L day (§16.3). */
  public Instant cutoff(Instant now) {
    try {
      LocalDate pnlDay = clock.calendar().currentPnlDay(now);
      LocalDate prev = clock.calendar().previousTradingDay(pnlDay);
      return clock.calendar().day(prev).orElseThrow().postCloseAt();
    } catch (RuntimeException e) {
      return Et.startOfDay(Et.date(now));
    }
  }

  /** The first of the last five trading days up to {@code today} (7 calendar days without a calendar). */
  public LocalDate fiveTradingDaysStart(LocalDate today) {
    try {
      LocalDate d = clock.calendar().tradingDayOnOrBefore(today);
      for (int i = 0; i < 4; i++) {
        d = clock.calendar().previousTradingDay(d);
      }
      return d;
    } catch (RuntimeException e) {
      return today.minusDays(6);
    }
  }

  /** Per position, keyed by symbol, including symbols sold out since the cut-off. */
  public Map<String, PositionDayPnl> positions(long accountId, List<PositionRow> held, Map<String, BigDecimal> marks,
                                               Instant now) {
    Instant cut = cutoff(now);
    Map<String, BigDecimal[]> today = new HashMap<>();   // symbol → {signed qty, Σ net}
    jdbc.sql("""
            SELECT symbol, SUM(CASE WHEN side = 'BUY' THEN qty ELSE -qty END) AS q, SUM(net_amount) AS net
              FROM execution WHERE account_id = ? AND executed_at > ? GROUP BY symbol""")
        .params(accountId, Jdbc.ts(cut))
        .query((rs, n) -> today.put(rs.getString("symbol"), new BigDecimal[] {rs.getBigDecimal("q"),
            rs.getBigDecimal("net")}))
        .list();
    Map<String, PositionDayPnl> out = new LinkedHashMap<>();
    Map<String, BigDecimal> qtyNow = new HashMap<>();
    held.forEach(p -> qtyNow.put(p.symbol(), p.qty()));
    java.util.Set<String> symbols = new java.util.TreeSet<>(qtyNow.keySet());
    symbols.addAll(today.keySet());
    for (String s : symbols) {
      BigDecimal q = qtyNow.getOrDefault(s, BigDecimal.ZERO);
      BigDecimal[] t = today.getOrDefault(s, new BigDecimal[] {BigDecimal.ZERO, Money.ZERO});
      BigDecimal mark = marks.get(s);
      BigDecimal mvNow = mark == null ? Money.ZERO : Rounding.MARKET_VALUE.multiply(q, mark);
      BigDecimal qtyPrev = q.subtract(t[0]);
      BigDecimal prevClose = prevClose(s, mark);
      BigDecimal mvPrev = prevClose == null ? Money.ZERO : Rounding.MARKET_VALUE.multiply(qtyPrev, prevClose);
      BigDecimal pnl = mvNow.subtract(mvPrev).add(t[1]);
      BigDecimal ret = mvPrev.signum() > 0 ? pnl.divide(mvPrev, 10, RoundingMode.HALF_EVEN) : null;
      out.put(s, new PositionDayPnl(s, pnl, ret));
    }
    return out;
  }

  /** {@code E_now − E_prev − flows}, with {@code U_now / U_prev − 1}. */
  public Account account(AccountRow acc, BigDecimal equityNow, BigDecimal unitValueNow, BigDecimal unitValueOpen,
                         Map<String, PositionDayPnl> positions, Instant now) {
    Instant cut = cutoff(now);
    if (!acc.openedAt().isBefore(cut)) {
      BigDecimal pnl = equityNow.subtract(acc.netContributions());
      return new Account(pnl, ratio(unitValueNow, unitValueOpen));
    }
    BigDecimal[] flows = jdbc.sql("""
            SELECT COALESCE(SUM(CASE WHEN is_contribution THEN amount ELSE 0 END), 0) AS f,
                   COALESCE(SUM(units_delta), 0) AS u
              FROM cash_transfer WHERE account_id = ? AND status = 'APPLIED' AND effective_at > ?""")
        .params(acc.id(), Jdbc.ts(cut))
        .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("f"), rs.getBigDecimal("u")}).single();
    BigDecimal positionsPnl = positions.values().stream().map(PositionDayPnl::dayPnl)
        .reduce(Money.ZERO, BigDecimal::add);
    BigDecimal otherCash = jdbc.sql("""
            SELECT COALESCE(SUM(amount), 0) FROM cash_ledger
             WHERE account_id = ? AND posted_at > ? AND entry_type IN ('DIVIDEND','CASH_IN_LIEU')""")
        .params(acc.id(), Jdbc.ts(cut)).query(BigDecimal.class).single();
    BigDecimal corrections = jdbc.sql("""
            SELECT COALESCE(SUM(amount), 0) FROM cash_transfer
             WHERE account_id = ? AND status = 'APPLIED' AND NOT is_contribution AND effective_at > ?""")
        .params(acc.id(), Jdbc.ts(cut)).query(BigDecimal.class).single();
    BigDecimal pnl = positionsPnl.add(otherCash).add(corrections).setScale(Money.SCALE, RoundingMode.HALF_EVEN);
    BigDecimal equityPrev = equityNow.subtract(flows[0]).subtract(pnl);
    BigDecimal unitsPrev = acc.units().subtract(flows[1]);
    BigDecimal uPrev = unitsPrev.signum() > 0 && equityPrev.signum() > 0
        ? Rounding.UNIT.divide(equityPrev, unitsPrev) : null;
    return new Account(pnl, ratio(unitValueNow, uPrev));
  }

  /** {@code now / then − 1} at scale 10; 0 when {@code then} is unknown or zero. */
  static BigDecimal ratio(BigDecimal now, BigDecimal then) {
    if (then == null || then.signum() <= 0 || now == null) {
      return BigDecimal.ZERO.setScale(10);
    }
    return now.divide(then, 10, RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE);
  }

  private BigDecimal prevClose(String symbol, BigDecimal markFallback) {
    try {
      return marketData.previousClose(symbol).orElse(markFallback);
    } catch (RuntimeException e) {
      return markFallback;
    }
  }
}
