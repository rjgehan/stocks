package com.stocks.league;

import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.time.TimeSource;
import com.stocks.league.LeaderboardComputer.Board;
import com.stocks.league.LeaderboardComputer.PeriodBoard;
import com.stocks.league.LeagueRepository.SnapshotRow;
import com.stocks.portfolio.EodSnapshotsWritten;
import com.stocks.portfolio.PnlCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * §16.4 steps 4–6, after {@code EodSnapshotJob} wrote day D (synchronously, on the job thread, so a failure fails the
 * job and catch-up retries it): {@code leaderboard_snapshot} for every period at metric RETURN and kind ALL (the
 * rank arrows), a NEW_LEADER feed event when the WEEK leader changed, and the feed backfill for D. Idempotent: MERGE
 * and {@code source_ref}.
 */
@Component
public class LeaderboardSnapshotWriter {

  private static final Logger log = LoggerFactory.getLogger(LeaderboardSnapshotWriter.class);

  private final LeaderboardComputer computer;
  private final LeagueRepository repository;
  private final FeedEventListener feed;
  private final PnlCalendar calendar;
  private final TimeSource time;

  public LeaderboardSnapshotWriter(LeaderboardComputer computer, LeagueRepository repository, FeedEventListener feed,
                                   PnlCalendar calendar, TimeSource time) {
    this.computer = computer;
    this.repository = repository;
    this.feed = feed;
    this.calendar = calendar;
    this.time = time;
  }

  @EventListener
  public void onEodSnapshots(EodSnapshotsWritten event) {
    write(event.tradeDate());
  }

  /** Writes D's leaderboard snapshot, NEW_LEADER and the feed backfill; returns the rows written. */
  public int write(LocalDate tradeDate) {
    Long previousWeekLeader = repository.previousLeader(LeaderboardPeriod.WEEK, tradeDate);
    Board board = computer.endOfDay(tradeDate);
    Instant now = time.now();
    int rows = 0;
    Map<LeaderboardPeriod, Long> leaders = new HashMap<>();
    for (PeriodBoard p : board.periods().values()) {
      for (var r : LeaderboardRowMapper.rows(p.entries(), LeaderboardMetric.RETURN, KindFilter.ALL, false,
          HoldingsVisibility.EOD_FULL)) {
        LeagueEntry e = p.entries().stream().filter(x -> x.player().publicId().equals(r.player().playerId()))
            .findFirst().orElseThrow();
        repository.upsertSnapshot(new SnapshotRow(p.period(), tradeDate, e.accountId(), e.player().id(), r.rank(),
            e.eligible(), e.twr(), e.pnl(), e.excess(), e.maxDrawdown(), e.equity(), e.netContributions(), e.fills(),
            e.symbolsTraded(), now));
        if (r.rank() != null && r.rank() == 1) {
          leaders.put(p.period(), e.player().id());
        }
        rows++;
      }
    }
    Long weekLeader = leaders.get(LeaderboardPeriod.WEEK);
    if (weekLeader != null && !weekLeader.equals(previousWeekLeader)) {
      feed.recordNewLeader(tradeDate, weekLeader, LeaderboardPeriod.WEEK, calendar.cutoff(tradeDate));
    }
    LocalDate previous = calendar.previousTradingDay(tradeDate);
    int backfilled = feed.backfill(calendar.cutoff(previous), calendar.cutoff(tradeDate));
    log.info("Leaderboard snapshot {}: {} row(s); feed backfill {} event(s)", tradeDate, rows, backfilled);
    return rows;
  }

  /** Rows of one period and day, by rank (tests, admin tools). */
  public List<SnapshotRow> rows(LeaderboardPeriod period, LocalDate tradeDate) {
    return repository.snapshots(period, tradeDate).stream()
        .sorted(Comparator.comparing(SnapshotRow::rank, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }
}
