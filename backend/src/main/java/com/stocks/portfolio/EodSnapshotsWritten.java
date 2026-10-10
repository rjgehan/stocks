package com.stocks.portfolio;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Published (synchronously, on the job thread) by {@link EodSnapshotJob} after it wrote every
 * {@code equity_snapshot} of trading day {@code tradeDate}. The league listens to finish §16.4 steps 4–6
 * ({@code leaderboard_snapshot}, the NEW_LEADER feed event and the feed backfill); listeners must be idempotent,
 * because the job re-runs a day after a re-mark or a catch-up.
 */
public record EodSnapshotsWritten(LocalDate tradeDate, Instant cutoff) {}
