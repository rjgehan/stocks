package com.stocks.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** §25 02:00 ET housekeeping (embedded PostgreSQL under -Ppg-it). */
class HousekeepingJobTest {

  @Test
  void runsAt2EtEveryDayAcrossDst() {
    HousekeepingJob job = new HousekeepingJob(null, TestTimes.clock(), new MockEnvironment());
    assertThat(job.name()).isEqualTo("HOUSEKEEPING");
    assertThat(job.catchUp()).isEqualTo(CatchUpPolicy.LATEST_ONLY);
    // Sat 2026-10-31 → Mon 2026-11-02: DST ends on Sunday 2026-11-01
    List<JobOccurrence> occ = job.occurrencesBetween(Instant.parse("2026-10-31T00:00:00Z"),
        Instant.parse("2026-11-02T12:00:00Z"), null);
    assertThat(occ).extracting(JobOccurrence::runKey).containsExactly("2026-10-31", "2026-11-01", "2026-11-02");
    assertThat(occ).extracting(JobOccurrence::at).containsExactly(Instant.parse("2026-10-31T06:00:00Z"),
        Instant.parse("2026-11-01T07:00:00Z"), Instant.parse("2026-11-02T07:00:00Z"));
    assertThat(job.occurrencesBetween(Instant.parse("2026-10-31T06:00:00Z"), Instant.parse("2026-10-31T07:00:00Z"),
        null)).as("from is exclusive").isEmpty();
  }

  @Test
  void deletesOnlyWhatIsPastItsRetention() {
    try (TestDb db = TestDb.fresh()) {
      deletes(db);
    }
  }

  private void deletes(TestDb db) {
    MutableTimeSource time = TestTimes.clock();
    Fixtures fx = new Fixtures(db.jdbc(), time);
    Fixtures.PlayerRef p = fx.human("keeper");
    Fixtures.AccountRef acc = fx.account(p, "500.00");
    Instant now = time.now();
    for (Instant expires : List.of(now.minusSeconds(1), now.plusSeconds(60))) {
      db.jdbc().sql("""
              INSERT INTO idempotency_record (scope, idem_key, request_hash, status_code, response_body, created_at,
                                              expires_at) VALUES ('admin.reset', ?, 'h', 200, '{}', ?, ?)""")
          .params("k" + expires.getEpochSecond(), Jdbc.ts(now), Jdbc.ts(expires)).update();
    }
    for (Instant at : List.of(now.minus(Duration.ofDays(15)), now.minus(Duration.ofDays(13)))) {
      db.jdbc().sql("""
              INSERT INTO intraday_equity (account_id, sampled_at, equity, unit_value, unit_series)
              VALUES (?, ?, 500.00, 1, 1)""").params(acc.id(), Jdbc.ts(at)).update();
    }
    int n = 0;
    for (Instant readAt : new Instant[] {now.minus(Duration.ofDays(91)), now.minus(Duration.ofDays(1)), null}) {
      db.jdbc().sql("""
              INSERT INTO notification (public_id, player_id, kind, title, body, source_ref, created_at, read_at)
              VALUES (?, ?, 'SYSTEM', 't', 'b', ?, ?, ?)""")
          .params("ntf_" + n, p.id(), "SYS:" + n, Jdbc.ts(now.minus(Duration.ofDays(100))), Jdbc.tsParam(readAt))
          .update();
      n++;
    }

    new HousekeepingJob(db.jdbc(), time, new MockEnvironment()).run("2026-10-09");

    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single()).isEqualTo(1);
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM intraday_equity").query(Long.class).single()).isEqualTo(1);
    assertThat(db.jdbc().sql("SELECT public_id FROM notification ORDER BY public_id").query(String.class).list())
        .containsExactly("ntf_1", "ntf_2");
  }
}
