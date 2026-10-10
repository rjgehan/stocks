package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.Timeframe;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.model.Bar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §25 schedules of the WS2 jobs (ET, calendar-aware, early closes move the 16:20/20:20 runs) and the split guard. */
class MarketJobsTest {

  static final MarketCalendar CAL = InMemoryCalendars.bundled();

  @Test
  void dailyMarkFollowsTheCloseIncludingEarlyCloses() {
    DailyMarkService marks = mock(DailyMarkService.class);
    DailyMarkJob job = new DailyMarkJob(marks, () -> Set.of("AAPL", "SPY"), () -> Set.of("SPY"));
    assertThat(job.catchUp()).isEqualTo(CatchUpPolicy.ALL_MISSED);
    List<JobOccurrence> normal = job.occurrencesBetween(Instant.parse("2026-10-09T01:00:00Z"),
        Instant.parse("2026-10-10T12:00:00Z"), CAL);
    assertThat(normal).containsExactly(new JobOccurrence(Instant.parse("2026-10-09T20:20:00Z"), "2026-10-09:OFFICIAL"),
        new JobOccurrence(Instant.parse("2026-10-10T00:20:00Z"), "2026-10-09"));
    List<JobOccurrence> early = job.occurrencesBetween(Instant.parse("2026-11-27T05:00:00Z"),
        Instant.parse("2026-11-28T05:00:00Z"), CAL);
    assertThat(early).extracting(JobOccurrence::at).containsExactly(Instant.parse("2026-11-27T18:20:00Z"),
        Instant.parse("2026-11-27T22:20:00Z"));
    job.run("2026-10-09:OFFICIAL");
    verify(marks).ensureOfficialCloses(LocalDate.of(2026, 10, 9), Set.of("AAPL", "SPY"));
    job.run("2026-10-09");
    verify(marks).ensureDayEndMarks(LocalDate.of(2026, 10, 9), Set.of("SPY"));
  }

  @Test
  void otherSchedules() {
    Instant from = Instant.parse("2026-10-09T00:00:00Z");
    Instant to = Instant.parse("2026-10-13T00:00:00Z");
    assertThat(new PrevCloseJob(null, null, null, null, Set::of, CAL).occurrencesBetween(from, to, CAL))
        .extracting(JobOccurrence::runKey).containsExactly("2026-10-09", "2026-10-12");      // trading days only
    assertThat(new RemarkJob(null, null).occurrencesBetween(from, to, CAL)).extracting(JobOccurrence::runKey)
        .containsExactly("2026-10-08:REMARK", "2026-10-09:REMARK");
    assertThat(new SplitGuardJob(null).occurrencesBetween(from, to, CAL)).extracting(JobOccurrence::at)
        .containsExactly(Instant.parse("2026-10-09T13:20:00Z"), Instant.parse("2026-10-12T13:20:00Z"));
    assertThat(new AssetSyncJob(null).occurrencesBetween(from, to, CAL)).hasSize(4)
        .allSatisfy(o -> assertThat(o.at().toString()).endsWith("T07:00:00Z"));
  }

  @Test
  void splitGuardInfersRatiosAndExDates() {
    List<Bar> raw = new ArrayList<>();
    List<Bar> adj = new ArrayList<>();
    String[][] days = {{"2026-10-12", "200.00", "100.00"}, {"2026-10-13", "202.00", "101.00"},
        {"2026-10-14", "100.50", "100.50"}, {"2026-10-15", "101.00", "101.00"}};
    for (String[] d : days) {
      raw.add(bar(d[0], d[1]));
      adj.add(bar(d[0], d[2]));
    }
    SplitGuard.Detected found = SplitGuard.detect("SIMS", raw, adj).orElseThrow();
    assertThat(found.exDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(found.oldRate()).isEqualByComparingTo("1");
    assertThat(found.newRate()).isEqualByComparingTo("2");
    List<Bar> rev = List.of(bar("2026-10-12", "1.00"), bar("2026-10-13", "10.00"));
    List<Bar> revAdj = List.of(bar("2026-10-12", "10.00"), bar("2026-10-13", "10.00"));
    SplitGuard.Detected r = SplitGuard.detect("REV", rev, revAdj).orElseThrow();
    assertThat(r.oldRate()).isEqualByComparingTo("10");
    assertThat(r.newRate()).isEqualByComparingTo("1");
    assertThat(SplitGuard.fraction(new BigDecimal("1.5"))).containsExactly(BigDecimal.valueOf(3), BigDecimal.TWO);
    assertThat(SplitGuard.detect("SAME", rev, rev)).isEmpty();
  }

  static Bar bar(String date, String close) {
    BigDecimal c = new BigDecimal(close);
    return new Bar("X", Timeframe.DAY1, com.stocks.common.time.Et.startOfDay(LocalDate.parse(date)), c, c, c, c, 1, c,
        1, false);
  }

  @Test
  void placeholderLogosArePngs() throws Exception {
    byte[] png = LogoService.placeholder("AAPL");
    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertThat(img.getWidth()).isEqualTo(64);
    assertThat(LogoService.placeholder("AAPL")).isEqualTo(png);
    LogoService upstreamFails = new LogoService(s -> {
      throw new IllegalStateException("down");
    });
    LogoService.Logo fallback = upstreamFails.logo("MSFT");
    assertThat(LogoService.isImage(fallback.bytes())).isTrue();
    assertThat(fallback.stable()).as("a placeholder standing in for a failure is not pinned").isFalse();
    LogoService upstream = new LogoService(s -> png);
    assertThat(upstream.logo("X").bytes()).isEqualTo(png);
    assertThat(upstream.logo("X").stable()).isTrue();
    assertThat(new LogoService(null).logo("SIM").stable()).as("no logo source: placeholders are final").isTrue();
  }

  /** Review regression (§15.5): a symbol without a logo used to cost one Alpaca call per request. */
  @Test
  void logoFailuresAreRememberedAndA403TurnsTheUpstreamOff() {
    java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    LogoService missing = new LogoService(s -> {
      calls.incrementAndGet();
      throw new com.stocks.marketdata.spi.ProviderException("alpaca", 404, "no logo", null, null);
    }, clock::get);
    for (int i = 0; i < 100; i++) {
      missing.logo("NOLOGO");
    }
    assertThat(calls.get()).isEqualTo(1);
    clock.addAndGet(LogoService.FAILURE_TTL.toNanos() + 1);
    missing.logo("NOLOGO");
    assertThat(calls.get()).as("retried after the negative TTL").isEqualTo(2);

    java.util.concurrent.atomic.AtomicInteger forbidden = new java.util.concurrent.atomic.AtomicInteger();
    LogoService noPlan = new LogoService(s -> {
      forbidden.incrementAndGet();
      throw new com.stocks.marketdata.spi.ProviderException("alpaca", 403, "forbidden", null, null);
    }, clock::get);
    for (int i = 0; i < 50; i++) {
      noPlan.logo("SYM" + i);
    }
    assertThat(forbidden.get()).as("one 403 turns logos off for every symbol").isEqualTo(1);
    clock.addAndGet(LogoService.FORBIDDEN_TTL.toNanos() + 1);
    noPlan.logo("LATER");
    assertThat(forbidden.get()).isEqualTo(2);
  }
}
