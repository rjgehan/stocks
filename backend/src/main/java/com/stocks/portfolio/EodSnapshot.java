package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One account at the P&amp;L cut-off of trading day {@code tradeDate} (§16.4): an {@code equity_snapshot} row, with
 * its {@code snapshot_holding} rows when loaded. {@code persisted} is false for a snapshot computed on the fly
 * (the EOD job has not written that day yet); it is then never stored by readers.
 *
 * @param holdings null when not loaded; otherwise every holding with a positive quantity at the cut-off
 */
public record EodSnapshot(long accountId, LocalDate tradeDate, Instant asOf, BigDecimal cash,
                          BigDecimal longMarketValue, BigDecimal dividendsReceivable, BigDecimal equity,
                          BigDecimal netContributions, BigDecimal units, BigDecimal unitValue, int unitSeries,
                          BigDecimal flowsInDay, BigDecimal pnlDay, int fillsDay, DataQuality marksQuality,
                          Instant computedAt, List<HoldingRow> holdings, boolean persisted) {

  /** One {@code snapshot_holding} row: quantity, the day-end mark and the market value at the cut-off. */
  public record HoldingRow(String symbol, BigDecimal qty, BigDecimal mark, BigDecimal marketValue) {}

  /** E − NC at the cut-off: the run-relative P&amp;L so far (§16.2). */
  public BigDecimal pnlSinceOpen() {
    return equity.subtract(netContributions);
  }

  public EodSnapshot withHoldings(List<HoldingRow> rows) {
    return new EodSnapshot(accountId, tradeDate, asOf, cash, longMarketValue, dividendsReceivable, equity,
        netContributions, units, unitValue, unitSeries, flowsInDay, pnlDay, fillsDay, marksQuality, computedAt,
        rows == null ? null : List.copyOf(rows), persisted);
  }
}
