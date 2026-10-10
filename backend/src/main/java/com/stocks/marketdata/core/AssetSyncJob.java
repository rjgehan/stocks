package com.stocks.marketdata.core;

import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** {@code ASSET_SYNC} (§25): 03:00 ET daily (and at boot); assets and liquidity tiers. {@code run_key} = date. */
public class AssetSyncJob implements ScheduledJob {

  static final LocalTime AT = LocalTime.of(3, 0);

  private final AssetSync sync;

  public AssetSyncJob(AssetSync sync) {
    this.sync = sync;
  }

  @Override
  public String name() {
    return "ASSET_SYNC";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    return EtSchedule.daily(fromExclusive, toInclusive, AT, d -> true);
  }

  @Override
  public void run(String runKey) {
    sync.sync();
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }
}
