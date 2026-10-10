package com.stocks.contract.marketdata;

import java.time.LocalDate;
import java.util.Set;

/** Daily marks (§15.5, WS2); called by WS4's {@code EodSnapshotJob}. Both methods are idempotent. */
public interface DailyMarkService {

  /** SIP 1Day closes → {@code daily_mark.official_close}. */
  void ensureOfficialCloses(LocalDate tradeDate, Set<String> symbols);

  /** §15.5 rules → {@code daily_mark.day_end_mark}. */
  void ensureDayEndMarks(LocalDate tradeDate, Set<String> symbols);
}
