package com.stocks.portfolio;

import com.stocks.common.time.TimeSource;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Period anchors (§16.3): the EOD state of an account for a past trading day. The {@code equity_snapshot} row when
 * the EOD job wrote one; otherwise (the job has not run for that day, e.g. before the first 20:20 after deploy, or a
 * run that never had a job) the same state computed on the fly by {@link EodValuator} and memoized until the next EOD
 * write. A day whose cut-off has not passed yet is never computed.
 */
@Component
public class EodSnapshots {

  private record Key(long accountId, LocalDate date) {}

  /** Bound for the memo: plenty for ~20 players × 6 anchors. */
  static final int MEMO_LIMIT = 5_000;
  /** Computed anchors are re-computed after this long (late day-end marks), wall time. */
  static final long MEMO_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

  private final EodSnapshotRepository repository;
  private final EodValuator valuator;
  private final PnlCalendar calendar;
  private final TimeSource time;
  private final Map<Key, Optional<EodSnapshot>> memo = new ConcurrentHashMap<>();
  private volatile long memoSince = System.nanoTime();

  public EodSnapshots(EodSnapshotRepository repository, EodValuator valuator, PnlCalendar calendar,
                      TimeSource time) {
    this.repository = repository;
    this.valuator = valuator;
    this.calendar = calendar;
    this.time = time;
  }

  /** The account's EOD state for {@code date}; empty if the run was not open at that cut-off (or it is future). */
  public Optional<EodSnapshot> get(AccountRow account, LocalDate date) {
    Optional<EodSnapshot> stored = repository.find(account.id(), date);
    if (stored.isPresent()) {
      return stored;
    }
    Instant cutoff = calendar.cutoff(date);
    if (time.now().isBefore(cutoff) || !account.openAt(cutoff)) {
      return Optional.empty();
    }
    Key key = new Key(account.id(), date);
    if (System.nanoTime() - memoSince > MEMO_TTL_NANOS) {
      invalidate();
    }
    Optional<EodSnapshot> cached = memo.get(key);
    if (cached != null) {
      return cached;
    }
    if (memo.size() > MEMO_LIMIT) {
      memo.clear();
    }
    Optional<EodSnapshot> computed = valuator.compute(account, date);
    memo.put(key, computed);
    return computed;
  }

  /** {@link #get} with holdings loaded (stored rows, or the computed ones). */
  public Optional<EodSnapshot> withHoldings(AccountRow account, LocalDate date) {
    return get(account, date).map(s -> {
      if (s.holdings() != null) {
        return s;
      }
      List<HoldingRow> rows = repository.holdings(s.accountId(), s.tradeDate());
      return s.withHoldings(rows);
    });
  }

  /** Forgets computed anchors, e.g. after the EOD job wrote real ones. */
  public void invalidate() {
    memo.clear();
    memoSince = System.nanoTime();
  }
}
