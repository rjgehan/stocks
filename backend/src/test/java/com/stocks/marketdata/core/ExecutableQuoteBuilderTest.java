package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.SessionType;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.config.ExecQuoteRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** §12.3 executable quote: confirmation after eligibility, freshness, the quality ladder, tiers and the clamp. */
class ExecutableQuoteBuilderTest {

  static final Instant NOW = Instant.parse("2026-10-09T14:30:00Z");
  static final Instant ELIGIBLE = NOW.minusSeconds(1);
  final ExecutableQuoteBuilder builder = new ExecutableQuoteBuilder(ExecQuoteRules.defaults());

  static Quote iex(String bid, String ask, Instant ts) {
    return new Quote("AAPL", new BigDecimal(bid), 300, new BigDecimal(ask), 200, "V", "V", ts, ts,
        DataQuality.REALTIME_SINGLE_VENUE, "alpaca");
  }

  static Trade sip(String price, Instant observedAt) {
    return new Trade("AAPL", new BigDecimal(price), 100, observedAt, observedAt, null, List.of(), true, true, "1",
        "finnhub");
  }

  static ExecutableQuoteBuilder.Inputs in(LiquidityTier tier, Quote q, Instant confirmedAt, boolean consolidated,
                                          Trade lastConsolidated) {
    return new ExecutableQuoteBuilder.Inputs("AAPL", tier, false, q, confirmedAt, consolidated,
        lastConsolidated == null ? List.of() : List.of(lastConsolidated), lastConsolidated, null);
  }

  Optional<ExecQuote> build(ExecutableQuoteBuilder.Inputs in, SessionType session) {
    return builder.build(in, NOW, ELIGIBLE, session);
  }

  @Test
  void rank1ConsolidatedQuote() {
    ExecQuote q = build(in(LiquidityTier.SMALL, iex("100.00", "100.50", NOW), NOW, true, null), SessionType.REGULAR)
        .orElseThrow();
    assertThat(q.quality()).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);
    assertThat(q.bid()).isEqualByComparingTo("100.00");
    assertThat(q.clamped()).isFalse();
  }

  @Test
  void simQuotesStaySimulated() {
    Quote sim = new Quote("AAPL", new BigDecimal("100.00"), 100, new BigDecimal("100.02"), 100, "SIM", "SIM", NOW,
        NOW, DataQuality.SIMULATED, "simulated");
    assertThat(build(in(LiquidityTier.MEGA, sim, NOW, true, null), SessionType.REGULAR).orElseThrow().quality())
        .isEqualTo(DataQuality.SIMULATED);
  }

  @Test
  void noQuoteConfirmedBeforeEligibility() {
    Quote q = iex("227.40", "227.42", NOW.minusSeconds(3));
    assertThat(build(in(LiquidityTier.MEGA, q, NOW.minusSeconds(2), false, null), SessionType.REGULAR)).isEmpty();
    // confirmed after eligibility (a later poll or stream message) → usable even though the quote itself is older
    assertThat(build(in(LiquidityTier.MEGA, q, NOW, false, null), SessionType.REGULAR)).isPresent();
  }

  @Test
  void freshnessThresholds() {
    Quote q = iex("227.40", "227.42", NOW.minusSeconds(4));
    ExecutableQuoteBuilder.Inputs stale = in(LiquidityTier.MEGA, q, NOW.minusSeconds(16), false, null);
    assertThat(builder.build(stale, NOW, NOW.minusSeconds(30), SessionType.REGULAR)).isEmpty();
    assertThat(builder.build(stale, NOW, NOW.minusSeconds(30), SessionType.POST)).isPresent();       // 120 s in EXT
    Quote old = iex("227.40", "227.42", NOW.minusSeconds(301));
    assertThat(build(in(LiquidityTier.MEGA, old, NOW, false, null), SessionType.REGULAR)).isEmpty();  // > 5 min
    assertThat(build(in(LiquidityTier.MEGA, old, NOW, false, null), SessionType.PRE)).isPresent();    // ≤ 15 min
  }

  @Test
  void rank2NarrowIexQuote() {
    ExecQuote q = build(in(LiquidityTier.MEGA, iex("227.40", "227.42", NOW), NOW, false, null), SessionType.REGULAR)
        .orElseThrow();
    assertThat(q.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    assertThat(q.ask()).isEqualByComparingTo("227.42");
    assertThat(q.clamped()).isFalse();
  }

  @Test
  void wideIexQuoteWithoutAPrintHasNoExecutableQuote() {
    // 0.8% wide: beyond MEGA (10 bps) and MID (40 bps) limits, inside SMALL (150 bps)
    Quote wide = iex("100.00", "100.80", NOW);
    assertThat(build(in(LiquidityTier.MEGA, wide, NOW, false, null), SessionType.REGULAR)).isEmpty();
    assertThat(build(in(LiquidityTier.MID, wide, NOW, false, null), SessionType.REGULAR)).isEmpty();
    assertThat(build(in(LiquidityTier.SMALL, wide, NOW, false, null), SessionType.REGULAR).orElseThrow().quality())
        .isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
  }

  @Test
  void wideIexQuoteWithAFreshPrintBecomesSynthetic() {
    Quote wide = iex("100.00", "100.80", NOW);
    ExecQuote q = build(in(LiquidityTier.MID, wide, NOW, false, sip("100.10", NOW.minusMillis(500))),
        SessionType.REGULAR).orElseThrow();
    assertThat(q.quality()).isEqualTo(DataQuality.SYNTHETIC);
    // half = max(tick, 100.10 × 10 bps) = 0.1001 → bid TICK_DOWN(100.00 − …) = 99.99, ask TICK_UP(100.2001) = 100.21
    assertThat(q.bid()).isEqualByComparingTo("99.99");
    assertThat(q.ask()).isEqualByComparingTo("100.21");
    assertThat(q.bidSize()).isEqualTo(100);
    // a print observed before eligibility does not count
    assertThat(build(in(LiquidityTier.MID, wide, NOW, false, sip("100.10", NOW.minusSeconds(5))),
        SessionType.REGULAR)).isEmpty();
  }

  @Test
  void syntheticHalfDoublesInExtendedHours() {
    ExecutableQuoteBuilder.Inputs in = in(LiquidityTier.MEGA, null, null, false, sip("200.00", NOW.minusSeconds(30)));
    ExecQuote ext = builder.build(in, NOW, NOW.minusSeconds(40), SessionType.POST).orElseThrow();
    // half = max(0.01, 200 × 2 bps = 0.04) × 2 = 0.08
    assertThat(ext.bid()).isEqualByComparingTo("199.92");
    assertThat(ext.ask()).isEqualByComparingTo("200.08");
    // 30 s old: too old for REGULAR (10 s)
    assertThat(builder.build(in, NOW, NOW.minusSeconds(40), SessionType.REGULAR)).isEmpty();
  }

  @Test
  void inconsistentIexQuoteFallsBackToTheTape() {
    // the tape printed far outside [bid − spread, ask + spread] in the last 10 s
    Quote q = iex("50.00", "50.10", NOW);
    ExecQuote x = build(in(LiquidityTier.SMALL, q, NOW, false, sip("51.00", NOW.minusMillis(500))),
        SessionType.REGULAR).orElseThrow();
    assertThat(x.quality()).isEqualTo(DataQuality.SYNTHETIC);
  }

  @Test
  void clampAroundAFreshPrint() {
    // SMALL: IEX 150 bps limit, h = max(tick, p × 50/2/10⁴); IEX touch 1% above a print 1 s old gets clamped
    Quote q = iex("100.90", "101.10", NOW);
    ExecQuote x = build(in(LiquidityTier.SMALL, q, NOW, false, sip("100.95", NOW.minusMillis(500))),
        SessionType.REGULAR).orElseThrow();
    // h = 100.95 × 0.0025 = 0.252375, 3h = 0.757125 → [100.19, 101.71]: the quote is inside → unchanged
    assertThat(x.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    assertThat(x.clamped()).isFalse();
    // consistent with the tape (print inside [bid − spread, ask + spread]) but the ask is beyond p + 3h:
    // p = 100.00, h = 0.25, window [99.25, 100.75] → ask 101.50 clamps to 100.75, bid 100.50 stays
    Quote far = iex("100.50", "101.50", NOW);
    ExecQuote y = build(in(LiquidityTier.SMALL, far, NOW, false, sip("100.00", NOW.minusMillis(500))),
        SessionType.REGULAR).orElseThrow();
    assertThat(y.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    assertThat(y.clamped()).isTrue();
    assertThat(y.bid()).isEqualByComparingTo("100.50");
    assertThat(y.ask()).isEqualByComparingTo("100.75");
    // a print older than 2 s does not clamp
    ExecQuote z = build(in(LiquidityTier.SMALL, far, NOW, false, sip("100.00", NOW.minusMillis(500))
        ), SessionType.REGULAR).orElseThrow();
    assertThat(z.clamped()).isTrue();
    ExecQuote old = builder.build(in(LiquidityTier.SMALL, far, NOW, false, sip("100.00", NOW.minusSeconds(3))),
        NOW, NOW.minusSeconds(5), SessionType.REGULAR).orElseThrow();
    assertThat(old.clamped()).isFalse();
    assertThat(old.ask()).isEqualByComparingTo("101.50");
  }

  @Test
  void haltedClosedCrossedOrOneSidedGiveNothing() {
    Quote q = iex("227.40", "227.42", NOW);
    ExecutableQuoteBuilder.Inputs halted = new ExecutableQuoteBuilder.Inputs("AAPL", LiquidityTier.MEGA, true, q, NOW,
        false, List.of(), null, null);
    assertThat(build(halted, SessionType.REGULAR)).isEmpty();
    assertThat(build(in(LiquidityTier.MEGA, q, NOW, false, null), SessionType.CLOSED)).isEmpty();
    assertThat(build(in(LiquidityTier.MEGA, iex("227.45", "227.40", NOW), NOW, false, null), SessionType.REGULAR))
        .isEmpty();
    assertThat(build(in(LiquidityTier.MEGA, iex("0", "227.40", NOW), NOW, false, null), SessionType.REGULAR))
        .isEmpty();
  }
}
