package com.stocks.portfolio;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.PositionRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import com.stocks.portfolio.RunAnchors.Anchor;
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
 * Run-relative account P&amp;L (§16.2, §16.3) for the account page, positions and WS3's {@code Account} DTO. Day P&amp;L
 * uses the TODAY board's anchor (EOD of {@code previousTradingDay(currentPnlDay(now))}), so the account page and the
 * leaderboard always agree:
 * <pre>
 * account  dayPnl   = E_now − E_prevEod − Σ flows since the prevEod cut-off;  dayReturn = U_now / U_prevEod − 1
 *          totalPnl = E − NC_run;                                            totalReturn = U_now / U_runOpen − 1
 * position dayPnl   = MV_now(s) − MV_prevEod(s) + Σ net_amount of executions in s since the prevEod cut-off
 * </pre>
 * A run opened after the anchor uses its opening instead (E − NC = 0, U of its INITIAL transfer).
 */
@Component
public class AccountPerformance {

  private final PortfolioRepository repository;
  private final ValuationService valuation;
  private final RunAnchors anchors;
  private final EodSnapshots snapshots;
  private final PnlCalendar calendar;
  private final JdbcClient jdbc;
  private final TimeSource time;

  public AccountPerformance(PortfolioRepository repository, ValuationService valuation, RunAnchors anchors,
                            EodSnapshots snapshots, PnlCalendar calendar, JdbcClient jdbc, TimeSource time) {
    this.repository = repository;
    this.valuation = valuation;
    this.anchors = anchors;
    this.snapshots = snapshots;
    this.calendar = calendar;
    this.jdbc = jdbc;
    this.time = time;
  }

  /** The TODAY anchor day for now: {@code previousTradingDay(currentPnlDay(now))}. */
  public LocalDate todayAnchorDate() {
    return calendar.previousTradingDay(calendar.currentPnlDay(time.now()));
  }

  public AccountPnl accountPnl(long accountId) {
    AccountRow run = run(accountId);
    List<TransferRow> transfers = repository.appliedTransfers(run.id());
    Anchor opening = RunAnchors.opening(run, transfers);
    if (!run.active() && run.finalEquity() != null && run.finalUnitValue() != null) {
      BigDecimal totalReturn = MetricsCalculator.twr(opening.unitPoint(),
          new UnitPoint(run.finalUnitValue(), run.unitSeries()));
      return new AccountPnl(Money.ZERO, BigDecimal.ZERO.setScale(MetricsCalculator.RETURN_SCALE),
          run.finalEquity().subtract(run.netContributions()), totalReturn, run.finalUnitValue(),
          run.closedAt() != null ? run.closedAt() : time.now());
    }
    Valuation v = valuation.valueNow(accountId);
    UnitPoint now = new UnitPoint(v.unitValue(), v.unitSeries());
    Anchor dayAnchor = anchors.forRun(run, todayAnchorDate());
    BigDecimal flows = RunAnchors.flowsAfter(dayAnchor, transfers, v.asOf());
    BigDecimal dayPnl = v.equity().subtract(dayAnchor.equity()).subtract(flows);
    BigDecimal dayReturn = MetricsCalculator.twr(dayAnchor.unitPoint(), now);
    BigDecimal totalPnl = v.equity().subtract(v.netContributions());
    BigDecimal totalReturn = MetricsCalculator.twr(opening.unitPoint(), now);
    return new AccountPnl(dayPnl, dayReturn, totalPnl, totalReturn, v.unitValue(), v.asOf());
  }

  public Map<String, PositionDayPnl> positionDayPnl(long accountId) {
    AccountRow run = run(accountId);
    List<PositionRow> positions = repository.positions(accountId);
    Map<String, PositionDayPnl> out = new LinkedHashMap<>();
    if (positions.isEmpty()) {
      return out;
    }
    Valuation v = valuation.valueNow(accountId);
    LocalDate anchorDate = todayAnchorDate();
    Instant anchorAt = calendar.cutoff(anchorDate);
    Map<String, BigDecimal> prevMv = new HashMap<>();
    if (run.openAt(anchorAt)) {
      snapshots.withHoldings(run, anchorDate).ifPresent(s -> {
        for (HoldingRow h : s.holdings()) {
          prevMv.merge(h.symbol(), h.marketValue(), BigDecimal::add);
        }
      });
    }
    Map<String, String> renamedFrom = renamesSince(accountId, anchorAt);
    Map<String, BigDecimal> netSince = new HashMap<>();
    jdbc.sql("SELECT symbol, SUM(net_amount) AS net FROM execution WHERE account_id = ? AND executed_at > ? "
            + "GROUP BY symbol")
        .params(accountId, Jdbc.ts(anchorAt))
        .query(rs -> {
          netSince.put(rs.getString("symbol"), rs.getBigDecimal("net"));
        });
    for (PositionRow p : positions) {
      Mark m = v.marks().get(p.symbol());               // null only if a fill landed between the two reads
      BigDecimal mvNow = m != null ? Rounding.marketValue(p.symbol(), p.qty(), m.price()) : p.costBasis();
      BigDecimal mvPrev = Money.ZERO;
      BigDecimal net = Money.ZERO;
      String name = p.symbol();
      int hops = 0;
      while (name != null && hops++ < 16) {             // the symbol and every name it had since the anchor
        mvPrev = mvPrev.add(prevMv.getOrDefault(name, Money.ZERO));
        net = net.add(netSince.getOrDefault(name, Money.ZERO));
        name = renamedFrom.get(name);
      }
      BigDecimal dayPnl = mvNow.subtract(mvPrev).add(net);
      BigDecimal dayReturn = mvPrev.signum() > 0
          ? dayPnl.divide(mvPrev, MetricsCalculator.RETURN_SCALE, RoundingMode.HALF_EVEN) : null;
      out.put(p.symbol(), new PositionDayPnl(p.symbol(), dayPnl, dayReturn));
    }
    return out;
  }

  /** NAME_CHANGE adjustments after {@code since}: new symbol → old symbol (§16.4 symbol renames). */
  private Map<String, String> renamesSince(long accountId, Instant since) {
    Map<String, String> out = new HashMap<>();
    jdbc.sql("""
            SELECT DISTINCT symbol_before, symbol_after FROM position_adjustment
             WHERE account_id = ? AND effective_at > ? AND symbol_before <> symbol_after""")
        .params(accountId, Jdbc.ts(since))
        .query(rs -> {
          out.put(rs.getString("symbol_after"), rs.getString("symbol_before"));
        });
    return out;
  }

  private AccountRow run(long accountId) {
    return repository.account(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + accountId + " does not exist."));
  }
}
