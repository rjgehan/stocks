package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WS2 repositories on a migrated database: every MERGE (with null parameters) and nullable binding runs on H2 and,
 * under {@code -Ppg-it}, on PostgreSQL 18.6 (§8, §27.5).
 */
class RepositoriesTest {

  TestDb db;
  MutableTimeSource time;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
  }

  @AfterEach
  void closeDb() throws Exception {
    ((AutoCloseable) db.dataSource()).close();         // PG has a connection limit: release the pool
  }

  static Asset asset(String symbol, String name, boolean fractionable, LiquidityTier tier) {
    return new Asset(symbol, name, "NASDAQ", AssetType.COMMON_STOCK, true, fractionable, false, false, false, tier,
        true);
  }

  @Test
  void assetUpsertKeepsTheAdvWhenNoneIsGiven() {
    AssetRepository repo = new AssetRepository(db.dataSource());
    repo.upsert(List.of(asset("AAPL", "Apple", true, LiquidityTier.MEGA), asset("ZZZ", "x".repeat(250), false,
        LiquidityTier.SMALL)), "alpaca", Map.of("AAPL", new BigDecimal("12345678901.25")), time.now());
    repo.upsert(List.of(asset("AAPL", "Apple Inc", false, LiquidityTier.MID)), "manual", Map.of(), time.now());
    Map<String, AssetRepository.Row> rows = new java.util.HashMap<>();
    repo.loadAll().forEach(r -> rows.put(r.asset().symbol(), r));
    assertThat(rows.get("AAPL").asset().name()).isEqualTo("Apple Inc");
    assertThat(rows.get("AAPL").asset().fractionable()).isFalse();
    assertThat(rows.get("AAPL").asset().tier()).isEqualTo(LiquidityTier.MID);
    assertThat(rows.get("AAPL").avgDollarVolume()).isEqualByComparingTo("12345678901.25");
    assertThat(rows.get("AAPL").source()).isEqualTo("manual");
    assertThat(rows.get("ZZZ").asset().name()).hasSize(200);
    assertThat(rows.get("ZZZ").avgDollarVolume()).isNull();
    assertThat(repo.countBySource("alpaca")).isEqualTo(1);
  }

  @Test
  void assetServiceSeedsAndSearches() {
    AssetService assets = new AssetService(new AssetRepository(db.dataSource()), Universe.load(), time);
    assets.reload();
    assertThat(assets.ensure(Universe.load().entries().stream().map(Universe.Entry::asset).toList(), "manual"))
        .isGreaterThan(500);
    assertThat(assets.ensure(List.of(asset("AAPL", "dup", true, LiquidityTier.MEGA)), "manual")).isZero();
    assertThat(assets.search("AAPL", 3).getFirst().symbol()).isEqualTo("AAPL");
    assertThat(assets.search("micro", 5)).extracting(Asset::symbol).contains("MSFT");
    assertThat(assets.search("", 5)).isEmpty();
    assertThat(assets.tier("NOPE")).isEqualTo(LiquidityTier.SMALL);
    assertThat(AssetService.tierFor(new BigDecimal("500000000"))).isEqualTo(LiquidityTier.MEGA);
    assertThat(AssetService.tierFor(new BigDecimal("19999999.99"))).isEqualTo(LiquidityTier.SMALL);
  }

  @Test
  void dailyMarksMergeWithNulls() {
    DailyMarkRepository repo = new DailyMarkRepository(db.dataSource());
    LocalDate d = LocalDate.of(2026, 10, 9);
    repo.upsertOfficialClose("AAPL", d, new BigDecimal("227.41"), time.now());
    repo.upsertDayEnd("AAPL", d, null, null, DailyMarkRepository.PROVISIONAL, time.now());
    assertThat(repo.officialClose("AAPL", d)).contains(new BigDecimal("227.4100"));
    assertThat(repo.dayEndMark("AAPL", d)).isEmpty();
    repo.upsertDayEnd("AAPL", d, new BigDecimal("227.9"), "SIP_1MIN", DailyMarkRepository.FINAL, time.now());
    repo.upsertDayEnd("MSFT", d, new BigDecimal("512.1"), "LAST_TRADE", DailyMarkRepository.PROVISIONAL, time.now());
    assertThat(repo.officialClose("AAPL", d)).contains(new BigDecimal("227.4100"));    // untouched by the day end
    assertThat(repo.dayEndMark("AAPL", d)).contains(new BigDecimal("227.9000"));
    assertThat(repo.withDayEnd(d, DailyMarkRepository.FINAL)).containsExactly("AAPL");
    assertThat(repo.provisional(d)).containsExactly("MSFT");
    assertThat(repo.officialCloses(d, Set.of("AAPL", "MSFT"))).containsOnlyKeys("AAPL");
    assertThat(repo.lastDayEnd("AAPL", d.plusDays(3)).orElseThrow().tradeDate()).isEqualTo(d);
    assertThat(repo.lastDayEnd("AAPL", d.minusDays(1))).isEmpty();
    // the official-close MERGE inserts a PROVISIONAL row without a day-end mark
    repo.upsertOfficialClose("NVDA", d, new BigDecimal("185"), time.now());
    assertThat(repo.dayEndMark("NVDA", d)).isEmpty();
    assertThat(db.jdbc().sql("SELECT quality FROM daily_mark WHERE symbol = 'NVDA'").query(String.class).single())
        .isEqualTo("PROVISIONAL");
  }

  @Test
  void splitAdjustments() {
    SplitAdjustmentsImpl splits = new SplitAdjustmentsImpl(db.dataSource(), time);
    LocalDate ex = LocalDate.of(2026, 10, 14);
    assertThat(splits.adjust("SIMS", new BigDecimal("200.00"), LocalDate.of(2026, 10, 13), ex))
        .isEqualByComparingTo("200.00");
    splits.record("SIMS", ex, BigDecimal.ONE, BigDecimal.TWO, 7);
    assertThat(splits.adjust("SIMS", new BigDecimal("200.00"), LocalDate.of(2026, 10, 13), ex))
        .isEqualByComparingTo("100.00");
    assertThat(splits.adjust("SIMS", new BigDecimal("200.00"), ex, ex.plusDays(3))).isEqualByComparingTo("200.00");
    assertThat(splits.adjust("SIMS", new BigDecimal("200.00"), LocalDate.of(2026, 10, 13), ex.minusDays(1)))
        .isEqualByComparingTo("200.00");
    splits.record("REV", ex, new BigDecimal("3"), BigDecimal.ONE, 8);              // 1-for-3 reverse
    assertThat(splits.adjust("REV", new BigDecimal("1.0000"), ex.minusDays(1), ex)).isEqualByComparingTo("3.0000");
    splits.record("ODD", ex, new BigDecimal("2"), new BigDecimal("3"), 9);          // 3-for-2: non-terminating
    assertThat(splits.adjust("ODD", new BigDecimal("100.00"), ex.minusDays(1), ex)).isEqualByComparingTo("66.6667");
    assertThat(splits.appliedOn("SIMS", ex)).isTrue();
    assertThat(splits.appliedOn("SIMS", ex.plusDays(1))).isFalse();
    assertThatThrownBy(() -> splits.record("X", ex, BigDecimal.ZERO, BigDecimal.ONE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    time.advance(Duration.ofSeconds(1));
    // a split written by another writer is seen after the TTL, or at once after invalidate() (an applied split)
    db.jdbc().sql("""
            INSERT INTO price_adjustment (symbol, ex_date, old_rate, new_rate, corporate_action_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)""")
        .params("LATE", ex, BigDecimal.ONE, BigDecimal.TWO, 10L, com.stocks.common.jdbc.Jdbc.ts(time.now())).update();
    assertThat(splits.adjust("LATE", new BigDecimal("10.00"), ex.minusDays(1), ex)).isEqualByComparingTo("10.00");
    splits.invalidate();
    assertThat(splits.adjust("LATE", new BigDecimal("10.00"), ex.minusDays(1), ex)).isEqualByComparingTo("5.00");
  }

  @Test
  void trackedSymbols() {
    TrackedSymbols tracked = new TrackedSymbols(db.dataSource());
    assertThat(tracked.held()).isEmpty();
    Fixtures fx = new Fixtures(db.jdbc(), time);
    Fixtures.PlayerRef alice = fx.human("alice");
    Fixtures.AccountRef acct = fx.account(alice, "500.00");
    fx.asset("AAPL");
    fx.position(acct, "AAPL", "1.5", "200.00");
    assertThat(tracked.held()).containsExactly("AAPL");
    assertThat(tracked.watched()).isEmpty();
  }
}
