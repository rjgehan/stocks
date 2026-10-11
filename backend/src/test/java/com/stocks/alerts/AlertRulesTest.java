package com.stocks.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.alerts.AlertRules.Live;
import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.contract.marketdata.ExtendedLine;
import com.stocks.contract.marketdata.QuoteView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** §19.1 alert rules: live prices only, conditions, re-arm hysteresis, validation and the notification text. */
class AlertRulesTest {

  static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");
  static final Duration MAX_AGE = Duration.ofMinutes(15);
  static final BigDecimal BAND = new BigDecimal("0.005");

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  /** A regular-session quote: last at lastAt, prevClose 225.00. */
  static QuoteView regular(String last, Instant lastAt, DataQuality quality, boolean halted) {
    BigDecimal l = last == null ? null : d(last);
    BigDecimal prev = d("225.00");
    BigDecimal change = l == null ? null : l.subtract(prev);
    return new QuoteView("AAPL", d("229.99"), 100L, d("230.01"), 100L, l, 10L, lastAt, prev, change,
        AlertRules.change(l, prev), null, null, null, null, false, null, lastAt, lastAt, quality, "test", halted);
  }

  /** An extended-hours quote: main line = close 230.00 (prevClose 225.00), extended line at extAt. */
  static QuoteView extended(SessionType session, String price, Instant extAt) {
    BigDecimal close = d("230.00");
    BigDecimal p = d(price);
    ExtendedLine ext = new ExtendedLine(session, p, p.subtract(close), AlertRules.change(p, close), extAt);
    return new QuoteView("AAPL", null, null, null, null, close, null, NOW.minus(Duration.ofHours(3)), d("225.00"),
        d("5.00"), AlertRules.change(close, d("225.00")), null, null, null, null, false, ext, extAt, extAt,
        DataQuality.REALTIME_SINGLE_VENUE, "test", false);
  }

  @Test
  void aRegularSessionUsesTheLastPrintAndItsChangeFromThePreviousClose() {
    Optional<Live> live = AlertRules.live(regular("230.15", NOW.minusSeconds(5), DataQuality.REALTIME_CONSOLIDATED,
        false), SessionType.REGULAR, NOW, MAX_AGE);
    assertThat(live).isPresent();
    assertThat(live.get().price()).isEqualByComparingTo("230.15");
    assertThat(live.get().changePct()).isEqualByComparingTo("0.0228888889");
  }

  @ParameterizedTest
  @EnumSource(value = DataQuality.class, names = {"STALE", "SYNTHETIC", "DELAYED_15M", "NONE"})
  void staleSyntheticDelayedAndEmptyQuotesNeverCount(DataQuality quality) {
    assertThat(AlertRules.live(regular("230.15", NOW, quality, false), SessionType.REGULAR, NOW, MAX_AGE)).isEmpty();
  }

  @Test
  void liveQualitiesAreRealtimeAndSimulated() {
    for (DataQuality q : new DataQuality[] {DataQuality.REALTIME_CONSOLIDATED, DataQuality.REALTIME_SINGLE_VENUE,
        DataQuality.SIMULATED}) {
      assertThat(AlertRules.live(regular("230.15", NOW, q, false), SessionType.REGULAR, NOW, MAX_AGE)).as(q.name())
          .isPresent();
    }
  }

  @Test
  void haltedOldMissingAndClosedMarketPricesNeverCount() {
    assertThat(AlertRules.live(regular("230.15", NOW, DataQuality.REALTIME_CONSOLIDATED, true), SessionType.REGULAR,
        NOW, MAX_AGE)).as("halted").isEmpty();
    assertThat(AlertRules.live(regular("230.15", NOW.minus(Duration.ofMinutes(16)),
        DataQuality.REALTIME_CONSOLIDATED, false), SessionType.REGULAR, NOW, MAX_AGE)).as("16 min old").isEmpty();
    assertThat(AlertRules.live(regular("230.15", NOW.minus(Duration.ofMinutes(15)),
        DataQuality.REALTIME_CONSOLIDATED, false), SessionType.REGULAR, NOW, MAX_AGE)).as("15 min old").isPresent();
    assertThat(AlertRules.live(regular(null, null, DataQuality.REALTIME_CONSOLIDATED, false), SessionType.REGULAR,
        NOW, MAX_AGE)).as("no print").isEmpty();
    assertThat(AlertRules.live(regular("230.15", NOW, DataQuality.REALTIME_CONSOLIDATED, false), SessionType.CLOSED,
        NOW, MAX_AGE)).as("closed").isEmpty();
    assertThat(AlertRules.live(null, SessionType.REGULAR, NOW, MAX_AGE)).isEmpty();
  }

  @Test
  void preMarketUsesTheExtendedLineNotTheClose() {
    Optional<Live> pre = AlertRules.live(extended(SessionType.PRE, "233.00", NOW.minusSeconds(30)), SessionType.PRE,
        NOW, MAX_AGE);
    assertThat(pre).isPresent();
    assertThat(pre.get().price()).isEqualByComparingTo("233.00");
    // in PRE the main line is the last regular close, which is the reference: +3.00 / 230.00
    assertThat(pre.get().changePct()).isEqualByComparingTo("0.0130434783");
    // a POST line seen during PRE (or none at all) is not live
    assertThat(AlertRules.live(extended(SessionType.POST, "233.00", NOW), SessionType.PRE, NOW, MAX_AGE)).isEmpty();
    QuoteView noExt = regular("230.00", NOW.minus(Duration.ofHours(12)), DataQuality.REALTIME_SINGLE_VENUE, false);
    assertThat(AlertRules.live(noExt, SessionType.PRE, NOW, MAX_AGE)).isEmpty();
  }

  @Test
  void afterHoursMeasuresTheDayFromThePreviousCloseNotTodaysClose() {
    Optional<Live> post = AlertRules.live(extended(SessionType.POST, "236.25", NOW.minusSeconds(30)),
        SessionType.POST, NOW, MAX_AGE);
    assertThat(post).isPresent();
    assertThat(post.get().price()).isEqualByComparingTo("236.25");
    assertThat(post.get().changePct()).isEqualByComparingTo("0.05");          // (236.25 - 225) / 225
  }

  @Test
  void conditionsHoldAtAndBeyondTheThreshold() {
    Live live = new Live(d("230.00"), d("0.05"), NOW);
    assertThat(AlertRules.holds(AlertCondition.PRICE_GTE, d("230.00"), live)).isTrue();
    assertThat(AlertRules.holds(AlertCondition.PRICE_GTE, d("230.01"), live)).isFalse();
    assertThat(AlertRules.holds(AlertCondition.PRICE_LTE, d("230.00"), live)).isTrue();
    assertThat(AlertRules.holds(AlertCondition.PRICE_LTE, d("229.99"), live)).isFalse();
    assertThat(AlertRules.holds(AlertCondition.CHANGE_PCT_GTE, d("0.05"), live)).isTrue();
    assertThat(AlertRules.holds(AlertCondition.CHANGE_PCT_GTE, d("0.0501"), live)).isFalse();
    assertThat(AlertRules.holds(AlertCondition.CHANGE_PCT_LTE, d("-0.03"), live)).isFalse();
    assertThat(AlertRules.holds(AlertCondition.CHANGE_PCT_LTE, d("-0.03"), new Live(d("1"), d("-0.0312"), NOW)))
        .isTrue();
    assertThat(AlertRules.holds(AlertCondition.CHANGE_PCT_GTE, d("0.01"), new Live(d("1"), null, NOW)))
        .as("unknown change").isFalse();
  }

  @Test
  void rearmingNeedsTheValueBackPastTheBand() {
    // PRICE_GTE 230: re-arms at <= 228.85 (230 x 0.995)
    assertThat(AlertRules.rearms(AlertCondition.PRICE_GTE, d("230"), new Live(d("228.86"), null, NOW), BAND))
        .isFalse();
    assertThat(AlertRules.rearms(AlertCondition.PRICE_GTE, d("230"), new Live(d("228.85"), null, NOW), BAND))
        .isTrue();
    // PRICE_LTE 200: re-arms at >= 201.00
    assertThat(AlertRules.rearms(AlertCondition.PRICE_LTE, d("200"), new Live(d("200.99"), null, NOW), BAND))
        .isFalse();
    assertThat(AlertRules.rearms(AlertCondition.PRICE_LTE, d("200"), new Live(d("201.00"), null, NOW), BAND))
        .isTrue();
    // CHANGE_PCT_GTE +5%: re-arms at <= +4.5% (points, not a fraction of the threshold)
    assertThat(AlertRules.rearms(AlertCondition.CHANGE_PCT_GTE, d("0.05"), new Live(d("1"), d("0.0451"), NOW), BAND))
        .isFalse();
    assertThat(AlertRules.rearms(AlertCondition.CHANGE_PCT_GTE, d("0.05"), new Live(d("1"), d("0.045"), NOW), BAND))
        .isTrue();
    // CHANGE_PCT_LTE -3%: re-arms at >= -2.5%
    assertThat(AlertRules.rearms(AlertCondition.CHANGE_PCT_LTE, d("-0.03"), new Live(d("1"), d("-0.025"), NOW),
        BAND)).isTrue();
    assertThat(AlertRules.rearms(AlertCondition.CHANGE_PCT_LTE, d("-0.03"), new Live(d("1"), null, NOW), BAND))
        .isFalse();
  }

  @Test
  void thresholdsAreValidatedPerCondition() {
    assertThat(AlertRules.validThreshold(AlertCondition.PRICE_GTE, d("230.5000"))).isEqualTo(d("230.5"));
    assertThat(AlertRules.validThreshold(AlertCondition.PRICE_LTE, d("0.0001"))).isEqualTo(d("0.0001"));
    assertThat(AlertRules.validThreshold(AlertCondition.PRICE_GTE, d("1E+2"))).isEqualTo(d("100"));
    assertThat(AlertRules.validThreshold(AlertCondition.CHANGE_PCT_LTE, d("-0.035"))).isEqualTo(d("-0.035"));
    assertThat(AlertRules.validThreshold(AlertCondition.CHANGE_PCT_GTE, d("10"))).isEqualTo(d("10"));
    for (String bad : new String[] {"0", "-1", "1000000", "1.23456"}) {
      assertThatThrownBy(() -> AlertRules.validThreshold(AlertCondition.PRICE_GTE, d(bad))).as(bad)
          .isInstanceOf(ApiException.class).hasMessageContaining("price threshold");
    }
    for (String bad : new String[] {"-1", "-1.5", "10.000001", "0.1234567"}) {
      assertThatThrownBy(() -> AlertRules.validThreshold(AlertCondition.CHANGE_PCT_GTE, d(bad))).as(bad)
          .isInstanceOf(ApiException.class).hasMessageContaining("change threshold");
    }
    assertThatThrownBy(() -> AlertRules.validThreshold(AlertCondition.PRICE_GTE, null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> AlertRules.validThreshold(null, d("1"))).isInstanceOf(ApiException.class);
  }

  @Test
  void theNotificationSaysWhatHappenedAndWhatTheAlertWas() {
    Live live = new Live(d("230.15"), d("0.0231"), NOW);
    assertThat(AlertRules.title("AAPL", AlertCondition.PRICE_GTE, d("230"))).isEqualTo("AAPL ≥ $230.00");
    assertThat(AlertRules.title("AAPL", AlertCondition.CHANGE_PCT_LTE, d("-0.03")))
        .isEqualTo("AAPL day change ≤ −3.00%");
    assertThat(AlertRules.body("AAPL", AlertCondition.PRICE_GTE, d("230"), live, null))
        .isEqualTo("AAPL is at $230.15 (+2.31% today). Your alert: price at or above $230.00.");
    assertThat(AlertRules.body("AAPL", AlertCondition.CHANGE_PCT_GTE, d("0.02"), live, " breakout "))
        .isEqualTo("AAPL is at $230.15 (+2.31% today). Your alert: day change at or above +2.00%. Note: breakout");
    assertThat(AlertRules.money(d("0.4256"))).isEqualTo("$0.4256");
    assertThat(AlertRules.money(d("99991"))).isEqualTo("$99,991.00");
    assertThat(AlertRules.money(d("1234567.5"))).isEqualTo("$1,234,567.50");
    assertThat(AlertRules.title("BRK.A", AlertCondition.PRICE_LTE, d("700000"))).isEqualTo("BRK.A ≤ $700,000.00");
    assertThat(AlertRules.percent(BigDecimal.ZERO)).isEqualTo("0.00%");
  }
}
