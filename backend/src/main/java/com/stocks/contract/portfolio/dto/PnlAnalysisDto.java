package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.HistoryRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code PnlAnalysis} (§16.10, Phase 2; {@code GET /account/pnl-analysis}, MCP {@code get_pnl_analysis}): the
 * caller's ACTIVE run over a range (run-relative: a range that starts before the run's opening starts at the opening).
 * {@code points} is the account as returns {@code U(t) / U(a) − 1} with the P&amp;L since {@code from};
 * {@code benchmarks} are SPY then the extra benchmarks (QQQ) over the same window; {@code bySymbol} splits the P&amp;L
 * by symbol, and {@code Σ bySymbol.total + other = pnl} ({@code other}: P&amp;L corrections).
 */
public record PnlAnalysisDto(HistoryRange range, Instant from, Instant asOf, @JsonProperty("return") BigDecimal ret,
                             BigDecimal pnl, List<EarningsPointDto> points, List<BenchmarkCurveDto> benchmarks,
                             List<SymbolPnlDto> bySymbol, BigDecimal other) {

  /** One point of the account curve: return and P&amp;L since the window's start. */
  public record EarningsPointDto(Instant t, @JsonProperty("return") BigDecimal ret, BigDecimal pnl) {}

  /**
   * One symbol's P&amp;L over the window (§16.10): {@code total = realized + unrealized + dividends};
   * {@code unrealized} is the change of unrealized P&amp;L; {@code held}: still held at the end.
   */
  public record SymbolPnlDto(String symbol, BigDecimal realized, BigDecimal unrealized, BigDecimal dividends,
                             BigDecimal total, boolean held) {}
}
