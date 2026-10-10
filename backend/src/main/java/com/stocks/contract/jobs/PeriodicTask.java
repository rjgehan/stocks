package com.stocks.contract.jobs;

import java.time.Duration;

/**
 * A fixed-rate loop with no {@code job_run} row (§25), e.g. the 5 s leaderboard recompute. Run by
 * {@code JobScheduler}; disabled with {@code stocks.jobs.enabled=false}.
 */
public interface PeriodicTask {

  String name();

  Duration interval();

  /** True to tick only while a market session (PRE, REGULAR, POST) is open. */
  boolean sessionsOnly();

  void tick();
}
