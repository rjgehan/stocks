package com.stocks.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** §23.4 runtime settings on a fresh database (H2, or embedded PostgreSQL under -Ppg-it: MERGE with null casts). */
class SettingsServiceImplTest {

  TestDb db;
  MutableTimeSource time;
  long admin;

  @org.junit.jupiter.api.AfterEach
  void closeDb() {
    db.close();
  }

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    admin = new com.stocks.testkit.Fixtures(db.jdbc(), time).admin("root").id();
  }

  SettingsServiceImpl service(StocksProperties props) {
    return new SettingsServiceImpl(db.jdbc(), time, props,
        new TransactionTemplate(new DataSourceTransactionManager(db.dataSource())));
  }

  @Test
  void seedsEveryDefaultOnceWithStartingCashFromTheEnvironment() {
    SettingsServiceImpl s = service(TestProperties.stocks("stocks.bootstrap.default-starting-cash", "1000"));
    assertThat(s.seedMissing()).isEqualTo(SettingKey.values().length);
    assertThat(s.seedMissing()).isZero();
    assertThat(s.defaultStartingCash()).isEqualByComparingTo("1000.00");
    assertThat(s.allowSelfReset()).isFalse();
    assertThat(s.seasonStart()).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(s.eligibilityMinFills()).isEqualTo(5);
    assertThat(s.eligibilityMinSymbols()).isEqualTo(2);
    assertThat(s.tradeFeedDelay()).isEqualTo(Duration.ofMinutes(15));
    assertThat(s.holdingsVisibility()).isEqualTo(HoldingsVisibility.EOD_WEIGHTS);
    assertThat(s.showDepositsInFeed()).isTrue();
    assertThat(s.botsRequireRationale()).isTrue();
    assertThat(s.humanKindExcludesApiTraders()).isFalse();
    assertThat(s.all()).containsEntry("league.defaultStartingCash", "1000.00").hasSize(10);
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM app_setting WHERE updated_by IS NULL").query(Long.class).single())
        .isEqualTo(10);

    // the environment seeds once: a later boot with another value keeps the admin's value
    SettingsServiceImpl later = service(TestProperties.stocks("stocks.bootstrap.default-starting-cash", "250"));
    later.seedMissing();
    assertThat(later.defaultStartingCash()).isEqualByComparingTo("1000.00");
  }

  @Test
  void putValidatesCanonicalizesAndUpserts() {
    SettingsServiceImpl s = service(TestProperties.stocks());
    // without a seed, put inserts (MERGE … WHEN NOT MATCHED)
    s.put("social.tradeFeedDelay", "pt30m", admin);
    assertThat(s.tradeFeedDelay()).isEqualTo(Duration.ofMinutes(30));
    s.put("social.tradeFeedDelay", "PT0S", admin);
    assertThat(s.tradeFeedDelay()).isZero();
    s.put("league.defaultStartingCash", "750", admin);
    assertThat(s.all()).containsEntry("league.defaultStartingCash", "750.00");
    s.put("social.holdingsVisibility", "hidden", admin);
    assertThat(s.holdingsVisibility()).isEqualTo(HoldingsVisibility.HIDDEN);
    s.put("league.allowSelfReset", "TRUE", admin);
    assertThat(s.allowSelfReset()).isTrue();
    assertThat(db.jdbc().sql("SELECT updated_by FROM app_setting WHERE setting_key = 'league.allowSelfReset'")
        .query(Long.class).single()).isEqualTo(admin);

    for (String[] bad : new String[][] {{"league.defaultStartingCash", "0.99"}, {"league.defaultStartingCash", "1.001"},
        {"league.defaultStartingCash", "abc"}, {"league.allowSelfReset", "yes"}, {"league.seasonStart", "tomorrow"},
        {"league.eligibility.minFills", "-1"}, {"social.tradeFeedDelay", "P8D"}, {"social.tradeFeedDelay", "-PT1M"},
        {"social.holdingsVisibility", "PUBLIC"}, {"no.such.key", "1"}, {"league.allowSelfReset", ""}}) {
      assertThatThrownBy(() -> s.put(bad[0], bad[1], admin)).as(bad[0] + "=" + bad[1])
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.SETTING_INVALID);
            assertThat(e.status()).isEqualTo(422);
          });
    }
  }

  @Test
  void putAllIsAllOrNothing() {
    SettingsServiceImpl s = service(TestProperties.stocks());
    s.seedMissing();
    assertThatThrownBy(() -> s.putAll(Map.of(SettingKey.ALLOW_SELF_RESET, "true",
        SettingKey.ELIGIBILITY_MIN_FILLS, "lots"), admin)).isInstanceOf(ApiException.class);
    assertThat(s.allowSelfReset()).isFalse();
  }

  @Test
  void aCorruptRowFallsBackToTheDefault() {
    SettingsServiceImpl s = service(TestProperties.stocks());
    s.seedMissing();
    db.jdbc().sql("UPDATE app_setting SET setting_value = 'garbage' WHERE setting_key = 'league.eligibility.minFills'")
        .update();
    s.invalidate();
    assertThat(s.eligibilityMinFills()).isEqualTo(5);
  }

  @Test
  void readsAreCachedUntilAWriteInvalidates() {
    SettingsServiceImpl s = service(TestProperties.stocks());
    s.seedMissing();
    assertThat(s.allowSelfReset()).isFalse();
    db.jdbc().sql("UPDATE app_setting SET setting_value = 'true' WHERE setting_key = 'league.allowSelfReset'").update();
    assertThat(s.allowSelfReset()).as("cached for 5 s").isFalse();
    s.put("bots.requireRationale", "false", admin);
    assertThat(s.allowSelfReset()).as("a write drops the cache").isTrue();
  }
}
