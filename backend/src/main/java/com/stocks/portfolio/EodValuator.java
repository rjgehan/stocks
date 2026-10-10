package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.portfolio.UnitMath;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.HoldingsReconstructor.Holding;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Computes one account's end-of-day state for trading day D at the cut-off {@code c = day(D).postCloseAt()}
 * (§16.4 step 2), without writing it:
 * <ul>
 *   <li>{@code cash = Σ ledger.amount WHERE posted_at ≤ c};</li>
 *   <li>holdings by forward replay ({@link HoldingsReconstructor}), marked at {@code dayEndMark(symbol, D)}
 *       ({@link MarkResolver#endOfDay});</li>
 *   <li>the point-in-time dividend receivable at {@code c} (§16.1);</li>
 *   <li>units, unit series and net contributions from the applied transfers with {@code effective_at ≤ c};</li>
 *   <li>{@code unit_value = UNIT(equity / units)}, {@code flows_in_day}, {@code fills_day} and
 *       {@code pnl_day = E(c) − E(prev cut-off) − flows in (prev cut-off, c]} (§16.2).</li>
 * </ul>
 */
@Component
public class EodValuator {

  private final PortfolioRepository repository;
  private final EodSnapshotRepository snapshots;
  private final HoldingsReconstructor holdings;
  private final MarkResolver marks;
  private final PnlCalendar calendar;
  private final JdbcClient jdbc;
  private final TimeSource time;

  public EodValuator(PortfolioRepository repository, EodSnapshotRepository snapshots, HoldingsReconstructor holdings,
                     MarkResolver marks, PnlCalendar calendar, JdbcClient jdbc, TimeSource time) {
    this.repository = repository;
    this.snapshots = snapshots;
    this.holdings = holdings;
    this.marks = marks;
    this.calendar = calendar;
    this.jdbc = jdbc;
    this.time = time;
  }

  /** The account's state at D's cut-off; empty if the run was not open then. */
  public Optional<EodSnapshot> compute(AccountRow account, LocalDate tradeDate) {
    Instant cutoff = calendar.cutoff(tradeDate);
    if (!account.openAt(cutoff)) {
      return Optional.empty();
    }
    State s = state(account, tradeDate, cutoff);
    LocalDate prevDate = calendar.previousTradingDay(tradeDate);
    Instant prevCutoff = calendar.cutoff(prevDate);
    BigDecimal prevEquity;
    if (account.openedAt().isAfter(prevCutoff)) {
      prevEquity = Money.ZERO;                       // opened during D: the INITIAL is one of D's flows
    } else {
      prevEquity = snapshots.find(account.id(), prevDate).map(EodSnapshot::equity)
          .orElseGet(() -> state(account, prevDate, prevCutoff).equity);
    }
    BigDecimal flowsInDay = Money.ZERO;
    for (TransferRow t : s.transfers) {
      if (t.contribution() && t.effectiveAt().isAfter(prevCutoff)) {
        flowsInDay = flowsInDay.add(t.amount());
      }
    }
    int fillsDay = jdbc.sql("SELECT COUNT(*) FROM execution WHERE account_id = ? AND executed_at > ? "
            + "AND executed_at <= ?")
        .params(account.id(), Jdbc.ts(prevCutoff), Jdbc.ts(cutoff)).query(Integer.class).single();
    BigDecimal pnlDay = s.equity.subtract(prevEquity).subtract(flowsInDay);
    return Optional.of(new EodSnapshot(account.id(), tradeDate, cutoff, s.cash, s.longMarketValue, s.receivable,
        s.equity, s.netContributions, s.units, s.unitValue, s.unitSeries, flowsInDay, pnlDay, fillsDay, s.quality,
        time.now(), s.holdings, false));
  }

  /** Everything except the day's flows and P&amp;L. */
  private State state(AccountRow account, LocalDate tradeDate, Instant cutoff) {
    BigDecimal cash = repository.cashAt(account.id(), cutoff);
    List<HoldingRow> rows = new ArrayList<>();
    List<DataQuality> qualities = new ArrayList<>();
    BigDecimal lmv = Money.ZERO;
    for (Holding h : holdings.holdingsAt(account.id(), cutoff).values()) {
      Mark m = marks.endOfDay(h.symbol(), tradeDate, cutoff, h.qty(), h.costBasis());
      BigDecimal mv = Rounding.MARKET_VALUE.multiply(h.qty(), m.price());
      rows.add(new HoldingRow(h.symbol(), h.qty(), m.price(), mv));
      qualities.add(m.quality());
      lmv = lmv.add(mv);
    }
    BigDecimal receivable = repository.receivableAt(account.id(), cutoff);
    BigDecimal equity = cash.add(lmv).add(receivable);
    BigDecimal units = Rounding.UNIT.apply(BigDecimal.ZERO);
    BigDecimal nc = Money.ZERO;
    int series = account.unitSeries();
    BigDecimal lastU = null;
    List<TransferRow> upTo = new ArrayList<>();
    boolean first = true;
    for (TransferRow t : repository.appliedTransfers(account.id())) {
      if (t.effectiveAt().isAfter(cutoff)) {
        break;
      }
      upTo.add(t);
      units = units.add(t.unitsDelta());
      if (t.contribution()) {
        nc = nc.add(t.amount());
      }
      if (t.unitValue() != null) {
        lastU = t.unitValue();
      }
      series = first ? t.unitSeries() : Math.max(series, t.unitSeries());
      first = false;
    }
    BigDecimal unitValue = UnitMath.unitValueOrLast(equity, units, lastU);
    return new State(cash, lmv, receivable, equity, nc, Rounding.UNIT.apply(units), unitValue, series,
        Qualities.worst(qualities), rows, upTo);
  }

  private record State(BigDecimal cash, BigDecimal longMarketValue, BigDecimal receivable, BigDecimal equity,
                       BigDecimal netContributions, BigDecimal units, BigDecimal unitValue, int unitSeries,
                       DataQuality quality, List<HoldingRow> holdings, List<TransferRow> transfers) {}
}
