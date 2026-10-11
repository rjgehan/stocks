package com.stocks.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.AlertStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.NotificationKind;
import com.stocks.common.events.CorporateActionApplied;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.alerts.dto.AlertDto;
import com.stocks.contract.alerts.dto.CreateAlertRequest;
import com.stocks.contract.alerts.dto.UpdateAlertRequest;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19.1 trigger semantics, driven through {@link AlertMonitor#evaluate()} with scripted quotes: one firing per trigger
 * (one-shot alerts, repeating alerts with hysteresis and cooldown, change alerts on a new trading day), live prices
 * only, expiry, leases, edits racing a firing, and corporate actions. Runs on H2 and on PostgreSQL ({@code -Ppg-it}).
 */
class AlertMonitorTest {

  AlertKit k;
  PlayerRef alice;
  Instant t;

  @BeforeEach
  void setUp() {
    k = new AlertKit();
    alice = k.fx.human("alice");
    k.market.prevClose("AAPL", "225.00").prevClose("NVDA", "100.00");
    t = TestTimes.START;
  }

  @AfterEach
  void tearDown() {
    k.close();
  }

  /** A live print now (and a fresh confirmed quote), then the QuoteBook callback. */
  void print(String symbol, String price) {
    t = t.plusSeconds(5);
    k.time.set(t);
    k.market.print(symbol, t, price, 100);
    k.market.publish(symbol);
  }

  AlertDto create(AlertCondition c, String threshold, boolean repeat) {
    return k.service.create(alice.id(), new CreateAlertRequest("AAPL", c, new BigDecimal(threshold), repeat, null,
        "my note")).alert();
  }

  AlertDto reload(AlertDto a) {
    return AlertService.dto(k.repo.byPublicId(alice.id(), a.id()).orElseThrow());
  }

  @Test
  void aOneShotAlertFiresOnceOnALivePriceAndTellsThePlayer() {
    AlertDto a = create(AlertCondition.PRICE_GTE, "230", false);
    print("AAPL", "229.99");
    assertThat(k.monitor.evaluate()).isZero();
    print("AAPL", "230.15");
    assertThat(k.monitor.evaluate()).isEqualTo(1);
    print("AAPL", "231.00");
    assertThat(k.monitor.evaluate()).as("never twice").isZero();

    AlertDto fired = reload(a);
    assertThat(fired.status()).isEqualTo(AlertStatus.TRIGGERED);
    assertThat(fired.armed()).isFalse();
    assertThat(fired.triggerCount()).isEqualTo(1);
    assertThat(fired.lastTriggerPrice()).isEqualByComparingTo("230.15");
    assertThat(fired.lastTriggeredAt()).isEqualTo(TestTimes.START.plusSeconds(10));
    List<NotificationDto> notes = k.alertsOf(alice.id());
    assertThat(notes).singleElement().satisfies(n -> {
      assertThat(n.title()).isEqualTo("AAPL ≥ $230.00");
      assertThat(n.body()).isEqualTo("AAPL is at $230.15 (+2.29% today). Your alert: price at or above $230.00. "
          + "Note: my note");
      assertThat(n.payload()).containsEntry("alertId", a.id()).containsEntry("symbol", "AAPL")
          .containsEntry("condition", "PRICE_GTE").containsEntry("price", "230.15");
    });
    assertThat(k.created).as("NotificationCreated → Web Push").singleElement()
        .satisfies(e -> assertThat(e.notification().kind()).isEqualTo(NotificationKind.PRICE_ALERT));
    assertThat(k.monitor.watchedSymbols()).as("nothing left to watch").isEmpty();
  }

  @Test
  void anAlertWhoseConditionAlreadyHoldsFiresOnTheNextLiveUpdate() {
    print("AAPL", "240.00");
    create(AlertCondition.PRICE_GTE, "230", false);
    assertThat(k.monitor.evaluate()).isEqualTo(1);
  }

  @Test
  void staleSyntheticHaltedAndOldQuotesNeverFire() {
    AlertDto a = create(AlertCondition.PRICE_GTE, "230", false);
    // a STALE quote with a crossing print
    t = t.plusSeconds(5);
    k.time.set(t);
    k.market.quote("AAPL", t, "230.10", 100, "230.20", 100, DataQuality.STALE).print("AAPL", t, "230.15", 10);
    k.market.publish("AAPL");
    assertThat(k.monitor.evaluate()).as("stale").isZero();
    t = t.plusSeconds(5);
    k.time.set(t);
    k.market.quote("AAPL", t, "230.10", 100, "230.20", 100, DataQuality.SYNTHETIC).print("AAPL", t, "230.15", 10);
    k.market.publish("AAPL");
    assertThat(k.monitor.evaluate()).as("synthetic (Est.)").isZero();
    // a live quote again, but the last print is 16 minutes old
    t = t.plus(Duration.ofMinutes(16));
    k.time.set(t);
    k.market.quote("AAPL", t, "230.10", 100, "230.20", 100, DataQuality.REALTIME_SINGLE_VENUE);
    k.market.publish("AAPL");
    assertThat(k.monitor.evaluate()).as("old print").isZero();
    // halted
    k.market.halt("AAPL", t, t.plusSeconds(60));
    print("AAPL", "230.30");
    assertThat(k.monitor.evaluate()).as("halted").isZero();
    assertThat(reload(a).status()).isEqualTo(AlertStatus.ACTIVE);
    // live and not halted
    t = t.plusSeconds(60);
    print("AAPL", "230.30");
    assertThat(k.monitor.evaluate()).isEqualTo(1);
  }

  @Test
  void theMarketMustBeOpen() {
    create(AlertCondition.PRICE_GTE, "230", false);
    t = Instant.parse("2026-10-10T15:00:00Z");                  // Saturday
    print("AAPL", "240.00");
    assertThat(k.monitor.evaluate()).isZero();
  }

  @Test
  void aRepeatingAlertRearmsOnlyPastTheBandAndAfterTheCooldown() {
    AlertDto a = create(AlertCondition.PRICE_GTE, "230", true);
    print("AAPL", "230.00");
    assertThat(k.monitor.evaluate()).isEqualTo(1);
    assertThat(reload(a).armed()).isFalse();
    assertThat(reload(a).status()).isEqualTo(AlertStatus.ACTIVE);
    // chattering around the threshold does nothing: 229.00 is above 228.85 (0.5% band)
    print("AAPL", "229.00");
    print("AAPL", "230.50");
    assertThat(k.monitor.evaluate()).isZero();
    // back past the band: re-armed, but the next crossing is within the 15-minute cooldown
    print("AAPL", "228.80");
    assertThat(k.monitor.evaluate()).isZero();
    assertThat(reload(a).armed()).isTrue();
    print("AAPL", "231.00");
    assertThat(k.monitor.evaluate()).as("cooldown").isZero();
    t = t.plus(Duration.ofMinutes(15));
    print("AAPL", "231.00");
    assertThat(k.monitor.evaluate()).isEqualTo(1);
    AlertDto twice = reload(a);
    assertThat(twice.triggerCount()).isEqualTo(2);
    assertThat(k.alertsOf(alice.id())).hasSize(2);
  }

  @Test
  void aChangeAlertRearmsOnANewTradingDay() {
    AlertDto a = k.service.create(alice.id(), new CreateAlertRequest("NVDA", AlertCondition.CHANGE_PCT_GTE,
        new BigDecimal("0.05"), true, null, null)).alert();
    print("NVDA", "105.00");                                    // +5% on Fri 2026-10-09
    assertThat(k.monitor.evaluate()).isEqualTo(1);
    print("NVDA", "106.00");
    assertThat(k.monitor.evaluate()).isZero();
    assertThat(reload(a).lastTriggeredAt()).isNotNull();
    // Mon 2026-10-12 (Columbus Day: the NYSE is open), still up 6% on its (unchanged, scripted) previous close
    t = Instant.parse("2026-10-12T14:00:00Z");
    print("NVDA", "106.00");
    assertThat(k.monitor.evaluate()).isEqualTo(1);
    assertThat(reload(a).triggerCount()).isEqualTo(2);
    List<NotificationDto> notes = k.alertsOf(alice.id());
    assertThat(notes.get(0).title()).isEqualTo("NVDA day change ≥ +5.00%");
    assertThat(notes.get(0).body()).startsWith("NVDA is at $106.00 (+6.00% today).");
  }

  @Test
  void anExpiredAlertNeverFiresAndIsMarkedExpired() {
    AlertDto a = k.service.create(alice.id(), new CreateAlertRequest("AAPL", AlertCondition.PRICE_GTE,
        new BigDecimal("230"), false, TestTimes.START.plusSeconds(30), null)).alert();
    k.monitor.evaluate();
    t = TestTimes.START.plusSeconds(30);
    print("AAPL", "231.00");
    assertThat(k.monitor.evaluate()).isZero();
    k.monitor.reloadAll();
    k.monitor.evaluate();
    assertThat(reload(a).status()).isEqualTo(AlertStatus.EXPIRED);
    assertThat(k.monitor.watchedSymbols()).isEmpty();
  }

  @Test
  void activeAlertsHoldOneListLeasePerSymbol() {
    AlertDto a = create(AlertCondition.PRICE_GTE, "230", false);
    AlertDto b = create(AlertCondition.PRICE_LTE, "200", false);
    k.monitor.evaluate();
    assertThat(k.market.leases()).containsEntry("AAPL", java.util.Set.of("alert:AAPL"));
    k.service.pause(alice.id(), a.id());
    k.monitor.evaluate();
    assertThat(k.market.leases().get("AAPL")).containsExactly("alert:AAPL");
    k.service.delete(alice.id(), b.id());
    k.monitor.evaluate();
    assertThat(k.market.leases().getOrDefault("AAPL", java.util.Set.of())).isEmpty();
    // a market update for a symbol without ACTIVE alerts is ignored
    print("AAPL", "250.00");
    assertThat(k.monitor.evaluate()).isZero();
    assertThat(k.alertsOf(alice.id())).isEmpty();
  }

  @Test
  void anEditRacingAFiringWinsAndTheOldConditionNeverFires() {
    AlertDto a = create(AlertCondition.PRICE_GTE, "230", false);
    k.monitor.evaluate();                                       // the monitor now holds version 1
    var held = k.repo.byPublicId(alice.id(), a.id()).orElseThrow();
    k.service.update(alice.id(), a.id(), new UpdateAlertRequest(AlertCondition.PRICE_GTE, new BigDecimal("300"),
        false, null, null));
    // the conditional UPDATE refuses the stale version
    assertThat(k.repo.fire(held.id(), held.version(), held.triggerCount(), false, new BigDecimal("231"),
        TestTimes.TRADE_DATE, k.time.now())).isZero();
    print("AAPL", "231.00");
    assertThat(k.monitor.evaluate()).isZero();
    assertThat(reload(a).status()).isEqualTo(AlertStatus.ACTIVE);
    print("AAPL", "300.00");
    assertThat(k.monitor.evaluate()).isEqualTo(1);
  }

  @Test
  void aSplitPausesTheSymbolsAlertsAndANameChangeMovesThem() {
    AlertDto a = create(AlertCondition.PRICE_GTE, "230", false);
    PlayerRef bob = k.fx.human("bob");
    k.service.create(bob.id(), new CreateAlertRequest("NVDA", AlertCondition.PRICE_LTE, new BigDecimal("90"), false,
        null, null));
    CorporateActionApplied split = new CorporateActionApplied("cac_split1", "AAPL", CorporateActionType.FORWARD_SPLIT,
        k.time.now());
    k.corporateActions.handle(split);
    k.corporateActions.handle(split);                           // redelivered: one notification
    assertThat(reload(a).status()).isEqualTo(AlertStatus.PAUSED);
    assertThat(k.alertsOf(alice.id())).singleElement()
        .satisfies(n -> assertThat(n.title()).isEqualTo("AAPL alerts paused after a split"));
    print("AAPL", "240.00");
    assertThat(k.monitor.evaluate()).as("paused alerts never fire").isZero();

    k.db.jdbc().sql("""
            INSERT INTO corporate_action (public_id, symbol, action_type, ex_date, new_symbol, status, origin, created_at)
            VALUES ('cac_rename1', 'NVDA', 'NAME_CHANGE', ?, 'NVDX', 'APPLIED', 'PROVIDER', ?)""")
        .params(TestTimes.TRADE_DATE, Jdbc.ts(k.time.now())).update();
    k.corporateActions.handle(new CorporateActionApplied("cac_rename1", "NVDA", CorporateActionType.NAME_CHANGE,
        k.time.now()));
    assertThat(k.service.list(bob.id(), null, null)).singleElement().satisfies(x -> {
      assertThat(x.symbol()).isEqualTo("NVDX");
      assertThat(x.status()).isEqualTo(AlertStatus.ACTIVE);
    });
    assertThat(k.alertsOf(bob.id())).singleElement()
        .satisfies(n -> assertThat(n.title()).isEqualTo("NVDA is now NVDX"));
  }
}
