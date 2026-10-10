package com.stocks.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.alerts.AlertService.Created;
import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.AlertStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.alerts.dto.AlertDto;
import com.stocks.contract.alerts.dto.CreateAlertRequest;
import com.stocks.contract.alerts.dto.UpdateAlertRequest;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19.1 / §18.2 alert CRUD on a real database (also on PostgreSQL with {@code -Ppg-it}: every nullable and temporal
 * bind of {@link AlertRepository} runs here with and without values): validation, idempotent create, limits, edits
 * that re-arm, pause/resume, ownership and list order.
 */
class AlertServiceTest {

  AlertKit k;
  PlayerRef alice;
  PlayerRef bob;

  @BeforeEach
  void setUp() {
    k = new AlertKit(new AlertProperties(true, 3, 5, Duration.ofMinutes(15), new BigDecimal("0.005"),
        Duration.ofMinutes(15), Duration.ofMillis(250), Duration.ofDays(90)));
    alice = k.fx.human("alice");
    bob = k.fx.human("bob");
  }

  @AfterEach
  void tearDown() {
    k.close();
  }

  static CreateAlertRequest req(String symbol, AlertCondition c, String threshold) {
    return new CreateAlertRequest(symbol, c, new BigDecimal(threshold), null, null, null);
  }

  @Test
  void createNormalizesAndStartsActiveAndArmed() {
    Created c = k.service.create(alice.id(), new CreateAlertRequest(" aapl ", AlertCondition.PRICE_GTE,
        new BigDecimal("230.5"), true, TestTimes.START.plus(Duration.ofDays(7)), "  breakout ​"));
    assertThat(c.created()).isTrue();
    AlertDto a = c.alert();
    assertThat(a.id()).startsWith("alr_");
    assertThat(a.symbol()).isEqualTo("AAPL");
    assertThat(a.threshold()).isEqualTo(new BigDecimal("230.5000"));
    assertThat(a.repeat()).isTrue();
    assertThat(a.status()).isEqualTo(AlertStatus.ACTIVE);
    assertThat(a.armed()).isTrue();
    assertThat(a.note()).isEqualTo("breakout");
    assertThat(a.expiresAt()).isEqualTo(TestTimes.START.plus(Duration.ofDays(7)));
    assertThat(a.triggerCount()).isZero();
    assertThat(a.lastTriggeredAt()).isNull();
    assertThat(a.lastTriggerPrice()).isNull();
    assertThat(a.createdAt()).isEqualTo(TestTimes.START);
    // a change alert keeps its fraction at scale 6
    AlertDto change = k.service.create(alice.id(), req("NVDA", AlertCondition.CHANGE_PCT_LTE, "-0.03")).alert();
    assertThat(change.threshold()).isEqualTo(new BigDecimal("-0.030000"));
    assertThat(change.repeat()).isFalse();
    assertThat(change.note()).isNull();
    assertThat(change.expiresAt()).isNull();
  }

  @Test
  void anIdenticalLiveAlertIsReturnedInsteadOfADuplicate() {
    AlertDto first = k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "230")).alert();
    Created again = k.service.create(alice.id(), req("aapl", AlertCondition.PRICE_GTE, "230.0000"));
    assertThat(again.created()).isFalse();
    assertThat(again.alert().id()).isEqualTo(first.id());
    // repeat is part of the identity; so is the player
    assertThat(k.service.create(alice.id(), new CreateAlertRequest("AAPL", AlertCondition.PRICE_GTE,
        new BigDecimal("230"), true, null, null)).created()).isTrue();
    assertThat(k.service.create(bob.id(), req("AAPL", AlertCondition.PRICE_GTE, "230")).created()).isTrue();
    // a paused one still counts as identical
    k.service.pause(alice.id(), first.id());
    assertThat(k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "230")).alert().id())
        .isEqualTo(first.id());
  }

  @Test
  void symbolsMustBeKnown() {
    assertThatThrownBy(() -> k.service.create(alice.id(), req("ZZZZ", AlertCondition.PRICE_GTE, "1")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SYMBOL_NOT_FOUND));
    assertThatThrownBy(() -> k.service.create(alice.id(), req("not a ticker", AlertCondition.PRICE_GTE, "1")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    k.fx.asset("MSFT");                                             // known through the asset table only
    assertThat(k.service.create(alice.id(), req("MSFT", AlertCondition.PRICE_LTE, "400")).alert().symbol())
        .isEqualTo("MSFT");
  }

  @Test
  void requestsAreValidated() {
    assertThatThrownBy(() -> k.service.create(alice.id(), new CreateAlertRequest("AAPL", AlertCondition.PRICE_GTE,
        new BigDecimal("1"), null, null, "x".repeat(141)))).hasMessageContaining("140");
    assertThatThrownBy(() -> k.service.create(alice.id(), new CreateAlertRequest("AAPL", AlertCondition.PRICE_GTE,
        new BigDecimal("1"), null, TestTimes.START, null))).hasMessageContaining("future");
    assertThatThrownBy(() -> k.service.create(alice.id(), new CreateAlertRequest("AAPL", AlertCondition.PRICE_GTE,
        new BigDecimal("1"), null, TestTimes.START.plus(Duration.ofDays(366)), null))).hasMessageContaining("365");
    assertThatCode(() -> k.service.create(alice.id(), req("AAPL", AlertCondition.CHANGE_PCT_GTE, "5")))
        .as("5 is +500%: allowed").doesNotThrowAnyException();
    assertThatThrownBy(() -> k.service.create(alice.id(), new CreateAlertRequest("AAPL", null, BigDecimal.ONE, null,
        null, null))).hasMessageContaining("condition");
  }

  @Test
  void liveAlertsAreLimitedAndFinishedOnesAreTrimmed() {
    AlertDto a = k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "1")).alert();
    k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "2"));
    AlertDto c = k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "3")).alert();
    assertThatThrownBy(() -> k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "4")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TOO_MANY_ALERTS));
    assertThat(k.service.create(bob.id(), req("AAPL", AlertCondition.PRICE_GTE, "4")).created()).as("per player")
        .isTrue();
    // finished alerts do not count toward the live limit, but at most max-stored − max-active (2) are kept
    k.db.jdbc().sql("UPDATE price_alert SET status = 'TRIGGERED' WHERE public_id IN (?, ?)").params(a.id(), c.id())
        .update();
    k.time.advance(Duration.ofSeconds(1));
    k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "5"));
    k.db.jdbc().sql("UPDATE price_alert SET status = 'EXPIRED' WHERE player_id = ? AND threshold = 5")
        .param(alice.id()).update();
    k.time.advance(Duration.ofSeconds(1));
    k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "6"));
    List<AlertDto> all = k.service.list(alice.id(), null, null);
    assertThat(all).hasSizeLessThanOrEqualTo(5);
    assertThat(all.stream().filter(x -> !x.status().isLive()).count()).isEqualTo(2);
    // reviving a finished alert needs room too
    k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "7"));
    AlertDto finished = all.stream().filter(x -> !x.status().isLive()).findFirst().orElseThrow();
    assertThatThrownBy(() -> k.service.update(alice.id(), finished.id(), new UpdateAlertRequest(
        AlertCondition.PRICE_GTE, new BigDecimal("9"), null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TOO_MANY_ALERTS));
  }

  @Test
  void anEditReplacesTheFieldsAndRearms() {
    AlertDto a = k.service.create(alice.id(), new CreateAlertRequest("AAPL", AlertCondition.PRICE_GTE,
        new BigDecimal("230"), true, TestTimes.START.plus(Duration.ofDays(1)), "old")).alert();
    k.db.jdbc().sql("UPDATE price_alert SET status = 'TRIGGERED', armed = FALSE, trigger_count = 1 WHERE public_id = ?")
        .param(a.id()).update();
    k.time.advance(Duration.ofMinutes(1));
    AlertDto edited = k.service.update(alice.id(), a.id(), new UpdateAlertRequest(AlertCondition.CHANGE_PCT_GTE,
        new BigDecimal("0.04"), null, null, null));
    assertThat(edited.status()).isEqualTo(AlertStatus.ACTIVE);
    assertThat(edited.armed()).isTrue();
    assertThat(edited.condition()).isEqualTo(AlertCondition.CHANGE_PCT_GTE);
    assertThat(edited.threshold()).isEqualByComparingTo("0.04");
    assertThat(edited.repeat()).as("a missing repeat is false").isFalse();
    assertThat(edited.expiresAt()).as("a missing expiry clears it").isNull();
    assertThat(edited.note()).isNull();
    assertThat(edited.triggerCount()).as("history stays").isEqualTo(1);
    assertThat(edited.updatedAt()).isAfter(a.updatedAt());
    assertThat(k.repo.byPublicId(alice.id(), a.id()).orElseThrow().version()).isEqualTo(2);
    // a paused alert stays paused through an edit
    k.service.pause(alice.id(), a.id());
    assertThat(k.service.update(alice.id(), a.id(), new UpdateAlertRequest(AlertCondition.PRICE_LTE,
        new BigDecimal("200"), true, null, "dip")).status()).isEqualTo(AlertStatus.PAUSED);
  }

  @Test
  void pauseResumeAndDeleteAreTheOwnersOnly() {
    AlertDto a = k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "230")).alert();
    assertThatThrownBy(() -> k.service.pause(bob.id(), a.id()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThat(k.service.delete(bob.id(), a.id())).isFalse();
    assertThat(k.service.pause(alice.id(), a.id()).status()).isEqualTo(AlertStatus.PAUSED);
    assertThat(k.service.pause(alice.id(), a.id()).status()).as("idempotent").isEqualTo(AlertStatus.PAUSED);
    AlertDto resumed = k.service.resume(alice.id(), a.id());
    assertThat(resumed.status()).isEqualTo(AlertStatus.ACTIVE);
    assertThat(resumed.armed()).isTrue();
    assertThat(k.service.delete(alice.id(), a.id())).isTrue();
    assertThat(k.service.delete(alice.id(), a.id())).isFalse();
    assertThat(k.service.list(alice.id(), null, null)).isEmpty();
  }

  @Test
  void theListIsByStatusThenNewestFirstAndFilters() {
    AlertDto old = k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_GTE, "1")).alert();
    k.time.advance(Duration.ofSeconds(1));
    AlertDto paused = k.service.create(alice.id(), req("NVDA", AlertCondition.PRICE_GTE, "2")).alert();
    k.service.pause(alice.id(), paused.id());
    k.time.advance(Duration.ofSeconds(1));
    AlertDto fresh = k.service.create(alice.id(), req("AAPL", AlertCondition.PRICE_LTE, "3")).alert();
    k.service.create(bob.id(), req("AAPL", AlertCondition.PRICE_LTE, "3"));
    assertThat(k.service.list(alice.id(), null, null)).extracting(AlertDto::id)
        .containsExactly(fresh.id(), old.id(), paused.id());
    assertThat(k.service.list(alice.id(), AlertStatus.PAUSED, null)).extracting(AlertDto::id)
        .containsExactly(paused.id());
    assertThat(k.service.list(alice.id(), null, "aapl")).extracting(AlertDto::id)
        .containsExactly(fresh.id(), old.id());
  }
}
