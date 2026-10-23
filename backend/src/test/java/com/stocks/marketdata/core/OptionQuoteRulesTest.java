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

/**
 * §30 option quotes: the executable quote is the contract's own fresh two-sided quote with a sane spread (no
 * synthetic quotes from prints), and the mark is the mid, the bid without an ask, half the ask without a bid.
 */
class OptionQuoteRulesTest {

  static final String OCC = "SPY261016C00660000";
  static final Instant NOW = Instant.parse("2026-10-09T14:30:00Z");
  final ExecutableQuoteBuilder builder = new ExecutableQuoteBuilder(ExecQuoteRules.defaults());

  static Quote q(String bid, String ask, Instant ts) {
    return new Quote(OCC, bid == null ? null : new BigDecimal(bid), 10, ask == null ? null : new BigDecimal(ask), 10,
        "C", "C", ts, ts, DataQuality.INDICATIVE, "alpaca");
  }

  Optional<ExecQuote> build(Quote quote, Instant confirmedAt, Trade last) {
    return builder.build(new ExecutableQuoteBuilder.Inputs(OCC, LiquidityTier.SMALL, false, quote, confirmedAt, true,
        last == null ? List.of() : List.of(last), last, last), NOW, NOW.minusSeconds(1), SessionType.REGULAR);
  }

  @Test
  void theSpreadRuleAllowsTenCentsOrHalfTheMid() {
    assertThat(ExecutableQuoteBuilder.saneOptionSpread(new BigDecimal("0.05"), new BigDecimal("0.15"))).isTrue();
    assertThat(ExecutableQuoteBuilder.saneOptionSpread(new BigDecimal("8.00"), new BigDecimal("9.50"))).isTrue();
    assertThat(ExecutableQuoteBuilder.saneOptionSpread(new BigDecimal("1.00"), new BigDecimal("3.00"))).isFalse();
    assertThat(ExecutableQuoteBuilder.saneOptionSpread(new BigDecimal("0.20"), new BigDecimal("0.40"))).isFalse();
    assertThat(ExecutableQuoteBuilder.saneOptionSpread(BigDecimal.ZERO, new BigDecimal("0.05"))).isFalse();
    assertThat(ExecutableQuoteBuilder.saneOptionSpread(new BigDecimal("1.10"), new BigDecimal("1.00"))).isFalse();
  }

  @Test
  void aFreshSaneQuoteIsExecutableAndKeepsItsIndicativeLabel() {
    ExecQuote x = build(q("4.05", "4.15", NOW), NOW, null).orElseThrow();
    assertThat(x.bid()).isEqualByComparingTo("4.05");
    assertThat(x.ask()).isEqualByComparingTo("4.15");
    assertThat(x.quality()).isEqualTo(DataQuality.INDICATIVE);
  }

  @Test
  void anAbsurdStaleOrOneSidedQuoteIsNotAndPrintsNeverStandIn() {
    Trade print = new Trade(OCC, new BigDecimal("4.10"), 5, NOW, NOW, "C", List.of(), true, true, "1", "alpaca");
    assertThat(build(q("1.00", "3.00", NOW), NOW, print)).isEmpty();
    assertThat(build(q("4.05", "4.15", NOW.minusSeconds(600)), NOW, print)).isEmpty();   // 10 minutes old
    assertThat(build(q("4.05", "4.15", NOW), NOW.minusSeconds(30), print)).isEmpty();    // not confirmed lately
    assertThat(build(q("0", "0.05", NOW), NOW, print)).isEmpty();
    assertThat(build(null, NOW, print)).isEmpty();
  }

  @Test
  void theOptionMarkIsTheMidOrTheBid() {
    assertThat(MarkService.optionMid(q("4.05", "4.15", NOW))).isEqualByComparingTo("4.10");
    assertThat(MarkService.optionMid(q("4.05", null, NOW))).isEqualByComparingTo("4.05");
    assertThat(MarkService.optionMid(q("0", "0.05", NOW))).isEqualByComparingTo("0.025");
    assertThat(MarkService.optionMid(q(null, null, NOW))).isNull();
    assertThat(MarkService.optionMid(null)).isNull();
  }
}
