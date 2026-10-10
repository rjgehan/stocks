package com.stocks.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.model.TradingDayData;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The database-backed calendar (§9.1): bundled rows seeded into the table, admin overrides (closed days and shortened
 * sessions, nullable close times through {@code MERGE}), and the provider sync replacing a window. Runs on H2 and,
 * with {@code -Ppg-it}, on PostgreSQL.
 */
class DbMarketCalendarTest {

  TestDb db;
  MutableTimeSource time;
  CalendarRepository repository;
  DbMarketCalendar calendar;
  CalendarAdminImpl admin;
  AtomicInteger replans;
  Fixtures.PlayerRef owner;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    repository = new CalendarRepository(db.dataSource());
    calendar = new DbMarketCalendar(repository, time);
    calendar.initialize();
    replans = new AtomicInteger();
    admin = new CalendarAdminImpl(repository, calendar, time, replans::incrementAndGet);
    owner = new Fixtures(db.jdbc(), time).admin("owner");
  }

  @AfterEach
  void closeDb() throws Exception {
    ((AutoCloseable) db.dataSource()).close();         // PG has a connection limit: release the pool
  }

  @Test
  void bundledRowsAreSeededOnce() {
    int rows = db.jdbc().sql("SELECT COUNT(*) FROM trading_day").query(Integer.class).single();
    assertThat(rows).isEqualTo(BundledCalendarLoader.load().size());
    new DbMarketCalendar(repository, time).initialize();
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM trading_day").query(Integer.class).single()).isEqualTo(rows);
    TradingDay d = calendar.day(LocalDate.of(2026, 10, 9)).orElseThrow();
    assertThat(d.settlementDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(repository.loadTradingDays().getFirst().tradeDate()).isEqualTo(LocalDate.of(2016, 1, 4));
  }

  @Test
  void closedOverrideRemovesTheDayAndReplans() {
    LocalDate d = LocalDate.of(2026, 10, 14);
    CalendarOverride o = admin.putOverride(d, true, null, null, "Snow day", owner.id());
    assertThat(o.closed()).isTrue();
    assertThat(o.rthCloseAt()).isNull();
    assertThat(o.createdByHandle()).isEqualTo("owner");
    assertThat(o.createdAt()).isEqualTo(time.now());
    assertThat(calendar.isTradingDay(d)).isFalse();
    assertThat(calendar.nextTradingDay(LocalDate.of(2026, 10, 13))).isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(replans).hasValue(1);
    assertThat(admin.overrides(d, d)).hasSize(1);
    admin.deleteOverride(d, owner.id());
    assertThat(calendar.isTradingDay(d)).isTrue();
    assertThat(replans).hasValue(2);
    assertThatThrownBy(() -> admin.deleteOverride(d, owner.id())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void shortenedSessionBecomesAnEarlyClose() {
    LocalDate d = LocalDate.of(2026, 10, 15);
    Instant rthClose = Et.at(d, LocalTime.of(14, 0));
    admin.putOverride(d, false, rthClose, null, "Early close", owner.id());
    TradingDay day = calendar.day(d).orElseThrow();
    assertThat(day.rthCloseAt()).isEqualTo(rthClose);
    assertThat(day.postCloseAt()).isEqualTo(Et.at(d, LocalTime.of(18, 0)));
    assertThat(day.earlyClose()).isTrue();
    assertThat(day.source()).isEqualTo("override");
    // replacing it (MERGE) with an explicit post close
    Instant post = Et.at(d, LocalTime.of(16, 30));
    admin.putOverride(d, false, rthClose, post, "Early close, short post", owner.id());
    assertThat(calendar.day(d).orElseThrow().postCloseAt()).isEqualTo(post);
    assertThat(repository.override(d).orElseThrow().reason()).isEqualTo("Early close, short post");
    // only the post close
    admin.putOverride(d, false, null, Et.at(d, LocalTime.of(18, 0)), "Short post", owner.id());
    assertThat(calendar.day(d).orElseThrow().rthCloseAt()).isEqualTo(Et.at(d, LocalTime.of(16, 0)));
    assertThat(repository.override(d).orElseThrow().rthCloseAt()).isNull();
  }

  @Test
  void overrideValidation() {
    assertThatThrownBy(() -> admin.putOverride(LocalDate.of(2026, 10, 10), true, null, null, "Saturday", owner.id()))
        .isInstanceOf(ApiException.class).hasMessageContaining("not a trading day");
    assertThatThrownBy(() -> admin.putOverride(LocalDate.of(2026, 10, 14), true,
        Et.at(LocalDate.of(2026, 10, 14), LocalTime.of(13, 0)), null, "x", owner.id()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> admin.putOverride(LocalDate.of(2026, 10, 14), false, null, null, "x", owner.id()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> admin.putOverride(LocalDate.of(2026, 10, 14), true, null, null, " ", owner.id()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> admin.putOverride(LocalDate.of(2026, 10, 14), false,
        Et.at(LocalDate.of(2026, 10, 14), LocalTime.of(9, 0)), null, "before open", owner.id()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> admin.overrides(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 1)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void providerSyncReplacesTheWindow() {
    AtomicInteger syncReplans = new AtomicInteger();
    LocalDate veterans = LocalDate.of(2026, 11, 11);
    CalendarSyncJob job = new CalendarSyncJob(repository, calendar, (from, to) -> {
      List<TradingDayData> rows = new ArrayList<>();
      for (NyseCalendarGenerator.Row r : NyseCalendarGenerator.generate(from, to)) {
        if (r.date().equals(veterans)) {
          continue;                                   // the provider says closed
        }
        LocalDate settle = r.date().equals(LocalDate.of(2026, 10, 9)) ? LocalDate.of(2026, 10, 14) : r.settlementDate();
        rows.add(new TradingDayData(r.date(), r.preOpen(), r.rthOpen(), r.rthClose(), r.postClose(), settle));
      }
      return rows;
    }, time, syncReplans::incrementAndGet);
    assertThat(job.sync()).isGreaterThan(400);
    assertThat(syncReplans).hasValue(1);
    assertThat(calendar.isTradingDay(veterans)).isFalse();
    assertThat(calendar.settlementDate(LocalDate.of(2026, 10, 9))).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(calendar.day(LocalDate.of(2026, 10, 9)).orElseThrow().source()).isEqualTo("alpaca");
    // outside the provider window the bundled rows still answer
    assertThat(calendar.day(LocalDate.of(2026, 1, 2)).orElseThrow().source()).isEqualTo("bundled");
    // a fresh calendar over the same table agrees
    DbMarketCalendar reloaded = new DbMarketCalendar(repository, time);
    reloaded.initialize();
    assertThat(reloaded.isTradingDay(veterans)).isFalse();
  }

  @Test
  void implausiblyShortProviderAnswersChangeNothing() {
    CalendarSyncJob job = new CalendarSyncJob(repository, calendar, (from, to) -> List.of(new TradingDayData(
        LocalDate.of(2026, 10, 9), LocalTime.of(4, 0), LocalTime.of(9, 30), LocalTime.of(16, 0), LocalTime.of(20, 0),
        LocalDate.of(2026, 10, 13))), time, null);
    assertThat(job.sync()).isZero();
    assertThat(calendar.isTradingDay(LocalDate.of(2026, 10, 12))).isTrue();
    assertThat(new CalendarSyncJob(repository, calendar, null, time, null).sync()).isZero();
    assertThat(new CalendarSyncJob(repository, calendar, (f, t) -> {
      throw new IllegalStateException("down");
    }, time, null).sync()).isZero();
  }

  @Test
  void syncJobRunsOnSundays() {
    CalendarSyncJob job = new CalendarSyncJob(repository, calendar, null, time, null);
    assertThat(job.occurrencesBetween(Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"),
        calendar)).extracting(o -> o.runKey()).containsExactly("2026-10-11", "2026-10-18");
  }

  @Test
  void alpacaRowsWithoutExtendedTimesGetDefaults() {
    TradingDay d = CalendarSyncJob.toTradingDay(new TradingDayData(LocalDate.of(2026, 11, 27), null,
        LocalTime.of(9, 30), LocalTime.of(13, 0), null, null));
    assertThat(d.earlyClose()).isTrue();
    assertThat(d.postCloseAt()).isEqualTo(Et.at(LocalDate.of(2026, 11, 27), LocalTime.of(17, 0)));
    assertThat(d.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 30));
  }

  @Test
  void alwaysOpenAdminRefusesOverrides() {
    CalendarAdminImpl none = new CalendarAdminImpl(repository, null, time, null);
    assertThatThrownBy(() -> none.putOverride(LocalDate.of(2026, 10, 14), true, null, null, "x", owner.id()))
        .isInstanceOf(ApiException.class);
    assertThat(none.overrides(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
  }
}
