package com.stocks.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.rules.dto.RulesDto;
import com.stocks.testkit.GoldenFiles;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

/**
 * The rulebook (§18.3 {@code Rules}): built from the live configuration, fee schedule and runtime settings, with a
 * version that changes exactly when a published rule changes. {@code golden/mcp/rules.json} is the reviewed snapshot
 * of the default rulebook (re-record with {@code -Dgolden.update=true} after an intended rule change, and update
 * {@code docs/rules.md} in the same PR, §27.5).
 */
class RulesServiceTest {

  MutableTimeSource time;
  TestDb db;
  MockEnvironment env;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    db = TestDb.fresh();
    env = new MockEnvironment();
  }

  RulesService service(SettingsService settings) {
    return new RulesService(env, TestProperties.stocks(), settings, db.jdbc(), time, JacksonConfig.newMapper());
  }

  RulesService withStubSettings() {
    return service(NotImplementedStub.of(SettingsService.class, "WS1"));
  }

  @Test
  void theDefaultRulebookMatchesTheGoldenSnapshot() {
    RulesDto rules = withStubSettings().rules();
    GoldenFiles.assertMatches("mcp", "rules", rules);
  }

  @Test
  void defaultsFollowTheSpecWhileSettingsAreStubbed() {
    RulesDto r = withStubSettings().rules();
    assertThat(r.asOf()).isEqualTo(TestTimes.START);
    assertThat(r.version()).startsWith("1.").hasSize(14);
    assertThat(r.account().startingCash()).isEqualByComparingTo("500.00");
    assertThat(r.account().settlement()).isEqualTo("T+1");
    assertThat(r.account().pdtEnforced()).isFalse();
    assertThat(r.account().shortSelling()).isFalse();
    assertThat(r.account().gfv().settledCashOnlyAt()).isEqualTo(3);
    assertThat(r.account().gfv().liquidationOnlyAt()).isEqualTo(4);
    assertThat(r.orders().gtcDays()).isEqualTo(60);
    assertThat(r.orders().maxOpenOrders()).isEqualTo(50);
    assertThat(r.orders().rationaleRequiredForAi()).isTrue();
    assertThat(r.orders().fractional().minBuyNotional()).isEqualByComparingTo("1.00");
    assertThat(r.orders().fractional().marketOnly()).isTrue();
    assertThat(r.orders().matrix()).extracting(RulesDto.OrderMatrixRowDto::type).containsExactly(OrderType.MARKET,
        OrderType.LIMIT, OrderType.STOP, OrderType.STOP_LIMIT, OrderType.TRAILING_STOP);
    assertThat(r.orders().matrix().get(4).timeInForce()).containsExactly(TimeInForce.DAY);
    assertThat(r.leaderboard().minFills()).isEqualTo(5);
    assertThat(r.leaderboard().minSymbols()).isEqualTo(2);
    assertThat(r.leaderboard().benchmark()).isEqualTo("SPY");
    assertThat(r.leaderboard().resetPolicy()).contains("never erases a loss");
    assertThat(r.social().tradeFeedDelay()).isEqualTo("PT15M");
    assertThat(r.social().holdingsVisibility()).isEqualTo(HoldingsVisibility.EOD_WEIGHTS);
    assertThat(r.sessions().overnight()).isFalse();
    assertThat(r.sessions().liveIexWindow()).as("SIM has no IEX window").isNull();
    assertThat(r.dataSources()).singleElement().asString().startsWith("SIM");
    assertThat(r.fairness()).hasSize(5);
    assertThat(r.rateLimits()).containsEntry("orderWritesPerMinute", 20).containsEntry("orderWritesPerDay", 300);
  }

  @Test
  void feesAreTheRowsInForceAndScheduled() {
    RulesDto r = withStubSettings().rules();
    // 2026-10-09: SEC (open-ended), TAF paused Q4 2026, TAF 2027 (scheduled), CAT; the expired 2026 TAF row is gone
    assertThat(r.fees()).extracting(RulesDto.RulesFeeDto::code).containsExactly(FeeCode.CAT, FeeCode.SEC,
        FeeCode.TAF, FeeCode.TAF);
    assertThat(r.fees()).filteredOn(f -> f.code() == FeeCode.TAF).extracting(RulesDto.RulesFeeDto::effectiveFrom)
        .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1));
    assertThat(r.fees()).filteredOn(f -> f.code() == FeeCode.SEC).singleElement().satisfies(f -> {
      assertThat(f.rate()).isEqualByComparingTo("0.0000206");
      assertThat(f.appliesTo()).isEqualTo(RulesDto.FeeAppliesTo.SELL);
      assertThat(f.rounding()).isEqualTo("CEIL_CENT");
    });
  }

  @Test
  void liveSettingsAndConfigurationFlowIntoTheRulebookAndChangeTheVersion() {
    String before = withStubSettings().rules().version();
    SettingsService settings = Mockito.mock(SettingsService.class);
    Mockito.when(settings.defaultStartingCash()).thenReturn(new BigDecimal("1000.00"));
    Mockito.when(settings.botsRequireRationale()).thenReturn(true);
    Mockito.when(settings.eligibilityMinFills()).thenReturn(3);
    Mockito.when(settings.eligibilityMinSymbols()).thenReturn(1);
    Mockito.when(settings.tradeFeedDelay()).thenReturn(Duration.ofMinutes(30));
    Mockito.when(settings.holdingsVisibility()).thenReturn(HoldingsVisibility.HIDDEN);
    env.setProperty("stocks.rules.gtc-days", "90");
    env.setProperty("stocks.marketdata.profile", "hybrid");

    RulesDto r = service(settings).rules();
    assertThat(r.account().startingCash()).isEqualByComparingTo("1000.00");
    assertThat(r.leaderboard().minFills()).isEqualTo(3);
    assertThat(r.social().tradeFeedDelay()).isEqualTo("PT30M");
    assertThat(r.social().holdingsVisibility()).isEqualTo(HoldingsVisibility.HIDDEN);
    assertThat(r.orders().gtcDays()).isEqualTo(90);
    assertThat(r.sessions().liveIexWindow()).isEqualTo("08:00–17:00");
    assertThat(r.dataSources()).hasSize(3).first().asString().startsWith("Alpaca Basic");
    assertThat(r.version()).isNotEqualTo(before);

    time.advance(Duration.ofHours(1));
    RulesDto later = service(settings).rules();
    assertThat(later.version()).as("asOf is not part of the version").isEqualTo(r.version());
    assertThat(later.asOf()).isEqualTo(TestTimes.START.plus(Duration.ofHours(1)));
  }

  @Test
  void configBindsTheEngineAndRulesKeysWithDefaults() {
    env.setProperty("stocks.engine.latency-base", "300ms");
    env.setProperty("stocks.rules.fractional.allow-limit", "true");
    RulesConfig config = RulesConfig.from(env);
    assertThat(config.engine().latencyBase()).isEqualTo(Duration.ofMillis(300));
    assertThat(config.engine().latencyJitterMax()).isEqualTo(Duration.ofMillis(500));
    assertThat(config.rules().fractional().allowLimit()).isTrue();
    assertThat(config.marketDataProfile()).isEqualTo("simulated");
    assertThat(RulesService.matrix(config.rules()).get(1).qtyForms()).contains("FRACTIONAL_SHARES");
    assertThat(Map.of("x", RulesService.latency(config.engine()))).hasValueSatisfying(
        new org.assertj.core.api.Condition<>(s -> s.contains("300 ms"), "300 ms"));
  }
}
