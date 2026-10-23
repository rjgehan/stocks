package com.stocks.trading.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.testkit.TestCalendar;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** §30 {@code OPTION_EXPIRY}: 30 minutes after the regular and the post-market close of trading days, catch-up all. */
class OptionExpiryJobTest {

  final OptionExpiryJob job = new OptionExpiryJob(mock(EngineCalls.class), mock(DailyMarkService.class),
      mock(JdbcClient.class));

  @Test
  void runsAfterTheCloseAndAfterThePostMarketOfTradingDays() {
    List<JobOccurrence> occ = job.occurrencesBetween(Instant.parse("2026-10-09T04:00:00Z"),
        Instant.parse("2026-10-12T23:59:00Z"), new TestCalendar());
    assertThat(occ).extracting(JobOccurrence::runKey)
        .containsExactly("2026-10-09:CLOSE", "2026-10-09:LATE", "2026-10-12:CLOSE");   // 12 Oct LATE is 00:30Z on the 13th
    assertThat(occ.get(0).at()).isEqualTo(Instant.parse("2026-10-09T20:30:00Z"));      // 16:30 ET
    assertThat(occ.get(1).at()).isEqualTo(Instant.parse("2026-10-10T00:30:00Z"));      // 20:30 ET
    // an early close (Friday after Thanksgiving, 13:00) settles at 13:30
    assertThat(job.occurrencesBetween(Instant.parse("2026-11-27T05:00:00Z"), Instant.parse("2026-11-27T20:00:00Z"),
        new TestCalendar())).extracting(JobOccurrence::at).containsExactly(Instant.parse("2026-11-27T18:30:00Z"));
    assertThat(job.catchUp()).isEqualTo(CatchUpPolicy.ALL_MISSED);
  }
}
