package com.stocks.portfolio;

import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The start state of a performance window for one run (§16.3): the run's EOD snapshot of the anchor day when the run
 * was open at that cut-off, otherwise the run's <b>opening</b> (the state right after its INITIAL transfer:
 * {@code E = NC = F₀}, U and series of that transfer, so {@code E − NC = 0}).
 */
@Component
public class RunAnchors {

  /**
   * @param date              the anchor trading day; null for an opening anchor
   * @param openingTransferId the INITIAL transfer an opening anchor already contains (flows after the anchor exclude
   *                          it); null for an EOD anchor
   */
  public record Anchor(long accountId, Instant at, LocalDate date, BigDecimal equity, BigDecimal netContributions,
                       BigDecimal unitValue, int unitSeries, Long openingTransferId) {

    public boolean opening() {
      return date == null;
    }

    /** E − NC at the anchor: the run-relative P&amp;L so far (0 at an opening). */
    public BigDecimal pnlBase() {
      return equity.subtract(netContributions);
    }

    public MetricsCalculator.UnitPoint unitPoint() {
      return new MetricsCalculator.UnitPoint(unitValue, unitSeries);
    }
  }

  private final PortfolioRepository repository;
  private final EodSnapshots snapshots;
  private final PnlCalendar calendar;

  public RunAnchors(PortfolioRepository repository, EodSnapshots snapshots, PnlCalendar calendar) {
    this.repository = repository;
    this.snapshots = snapshots;
    this.calendar = calendar;
  }

  /** The run's anchor for trading day {@code anchorDate} (null = its opening). */
  public Anchor forRun(AccountRow run, LocalDate anchorDate) {
    if (anchorDate != null && run.openAt(calendar.cutoff(anchorDate))) {
      EodSnapshot s = snapshots.get(run, anchorDate).orElse(null);
      if (s != null) {
        return fromSnapshot(s);
      }
    }
    return opening(run, repository.appliedTransfers(run.id()));
  }

  public static Anchor fromSnapshot(EodSnapshot s) {
    return new Anchor(s.accountId(), s.asOf(), s.tradeDate(), s.equity(), s.netContributions(), s.unitValue(),
        s.unitSeries(), null);
  }

  /** The state right after the run's first applied transfer (its INITIAL funding). */
  public static Anchor opening(AccountRow run, List<TransferRow> transfers) {
    if (transfers.isEmpty()) {
      return new Anchor(run.id(), run.openedAt(), null, Money.ZERO, Money.ZERO,
          Rounding.UNIT.apply(BigDecimal.ONE), run.unitSeries(), null);
    }
    TransferRow first = transfers.get(0);
    BigDecimal u = first.unitValue() != null && first.unitValue().signum() > 0 ? first.unitValue()
        : BigDecimal.ONE;
    BigDecimal nc = first.contribution() ? first.amount() : Money.ZERO;
    return new Anchor(run.id(), first.effectiveAt(), null, first.amount(), nc, Rounding.UNIT.apply(u),
        first.unitSeries(), first.id());
  }

  /**
   * Σ contribution flows of the run after the anchor and at or before {@code end}: {@code effective_at} after an EOD
   * cut-off, or every transfer but the INITIAL for an opening anchor.
   */
  public static BigDecimal flowsAfter(Anchor anchor, List<TransferRow> transfers, Instant end) {
    BigDecimal sum = Money.ZERO;
    for (TransferRow t : transfers) {
      if (!t.contribution() || t.effectiveAt().isAfter(end)) {
        continue;
      }
      boolean after = anchor.opening() ? !Long.valueOf(t.id()).equals(anchor.openingTransferId())
          : t.effectiveAt().isAfter(anchor.at());
      if (after) {
        sum = sum.add(t.amount());
      }
    }
    return sum;
  }
}
