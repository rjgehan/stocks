package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.calendar.SessionChanged;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.trading.EngineState;
import com.stocks.contract.trading.EngineStatus;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.trading.account.AccountCommands;
import com.stocks.trading.account.TransferCommands;
import com.stocks.trading.engine.EngineCommand.ApplyCashTransfersCmd;
import com.stocks.trading.engine.EngineCommand.CancelAllCmd;
import com.stocks.trading.engine.EngineCommand.CancelOrderCmd;
import com.stocks.trading.engine.EngineCommand.CheckInvariantsCmd;
import com.stocks.trading.engine.EngineCommand.DailyMaintenanceCmd;
import com.stocks.trading.engine.EngineCommand.HaltEngineCmd;
import com.stocks.trading.engine.EngineCommand.LiftRestrictionCmd;
import com.stocks.trading.engine.EngineCommand.OpenAccountCmd;
import com.stocks.trading.engine.EngineCommand.PlaceOrderCmd;
import com.stocks.trading.engine.EngineCommand.RecordAdminViewCmd;
import com.stocks.trading.engine.EngineCommand.ReplaceOrderCmd;
import com.stocks.trading.engine.EngineCommand.ResetAccountCmd;
import com.stocks.trading.engine.EngineCommand.RestrictAccountCmd;
import com.stocks.trading.engine.EngineCommand.ResumeEngineCmd;
import com.stocks.trading.engine.EngineCommand.SweepCmd;
import com.stocks.trading.engine.EngineCommand.TransitionCmd;
import com.stocks.trading.order.SessionOrderMatrix;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine's logic, independent of the thread that drives it (§12): {@link EngineRunner} calls it from
 * {@code engine-main}, a test's {@code DirectEngineExecutor} from the calling thread. Every command runs in exactly one
 * transaction; events and in-memory index changes happen only after commit (§4 rules 1–2).
 */
@Component
public class EngineCore {

  private static final Logger log = LoggerFactory.getLogger(EngineCore.class);
  static final Duration RE_EVALUATE = Duration.ofSeconds(1);

  private final TransactionTemplate tx;
  private final ApplicationEventPublisher publisher;
  private final OrderHandler orders;
  private final Evaluator evaluator;
  private final AccountCommands accountCommands;
  private final TransferCommands transferCommands;
  private final InvariantChecker invariants;
  private final OrderStore orderStore;
  private final AccountStore accountStore;
  private final TradingClock clock;
  private final MarketDataService marketData;
  private final OpenOrderIndex index;
  private final JdbcClient jdbc;
  private final PrintBuffer prints = new PrintBuffer();
  private final Set<String> dirty = ConcurrentHashMap.newKeySet();

  private volatile EngineState state = EngineState.STARTING;
  private volatile String haltReason;
  private volatile String lastError;
  private volatile InvariantResult lastInvariants;
  private volatile long lastSeq;
  private volatile Instant lastCommandAt;
  private volatile boolean bootstrapped;

  public EngineCore(PlatformTransactionManager txManager, ApplicationEventPublisher publisher, OrderHandler orders,
                    Evaluator evaluator, AccountCommands accountCommands, TransferCommands transferCommands,
                    InvariantChecker invariants, OrderStore orderStore, AccountStore accountStore, TradingClock clock,
                    MarketDataService marketData, OpenOrderIndex index, JdbcClient jdbc) {
    this.tx = new TransactionTemplate(txManager);
    this.publisher = publisher;
    this.orders = orders;
    this.evaluator = evaluator;
    this.accountCommands = accountCommands;
    this.transferCommands = transferCommands;
    this.invariants = invariants;
    this.orderStore = orderStore;
    this.accountStore = accountStore;
    this.clock = clock;
    this.marketData = marketData;
    this.index = index;
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- bootstrap

  /** §12.9 steps 4 and 6: invariants, then the open orders and their leases. Idempotent. */
  public synchronized void bootstrap() {
    EngineThread.assertCurrent();
    if (bootstrapped) {
      return;
    }
    index.clear();
    for (OrderRow r : orderStore.open()) {
      orders.track(r);
    }
    InvariantResult result = invariants.check();
    lastInvariants = result;
    if (result.ok()) {
      state = EngineState.RUNNING;
    } else {
      halt("Invariants failed at boot: " + result.failures().get(0));
    }
    bootstrapped = true;
    log.info("Engine {} with {} open orders", state, index.size());
  }

  public boolean isBootstrapped() {
    return bootstrapped;
  }

  // ------------------------------------------------------------------------------------------- commands

  /** Runs one command in one transaction; publishes its events after commit. Engine thread only. */
  @SuppressWarnings("unchecked")
  public <R> R execute(EngineCommand cmd) {
    EngineThread.assertCurrent();
    lastSeq++;
    lastCommandAt = clock.now();
    if (state == EngineState.HALTED && !cmd.allowedWhileHalted()) {
      throw ApiException.of(ErrorCode.ENGINE_HALTED, "The trading engine is halted"
              + (haltReason == null ? "." : ": " + haltReason))
          .withHint("An admin must resume the engine; read-only endpoints keep working.").withRetryAfter(60);
    }
    if (cmd instanceof SweepCmd) {
      sweep();
      return null;
    }
    return (R) inTransaction(t -> dispatch(cmd, t));
  }

  private Object dispatch(EngineCommand cmd, EngineTx t) {
    Instant now = clock.now();
    return switch (cmd) {
      case PlaceOrderCmd c -> orders.place(c, t);
      case ReplaceOrderCmd c -> orders.replace(c, t);
      case CancelOrderCmd c -> orders.cancel(c, t);
      case CancelAllCmd c -> orders.cancelAll(c, t);
      case SweepCmd c -> null;
      case TransitionCmd c -> transition(c, now, t);
      case ApplyCashTransfersCmd c -> transferCommands.apply(c.transfer(), c.idem(), c.adminPlayerId(), now, t);
      case OpenAccountCmd c -> accountCommands.open(c.playerId(), c.startingCash(), c.actorPlayerId(), now, t);
      case ResetAccountCmd c -> reset(c, now, t);
      case RestrictAccountCmd c -> {
        cancelAllOf(c.playerId(), c.cancelReason(), c.actorPlayerId(), now, t);
        accountCommands.restrict(c.playerId(), c.restriction(), c.reason(), now, t);
        yield null;
      }
      case LiftRestrictionCmd c -> {
        accountCommands.liftAdminRestriction(c.playerId(), now, t);
        yield null;
      }
      case RecordAdminViewCmd c -> {
        accountCommands.recordAdminView(c.accountId(), c.reason(), c.actorPlayerId(), now, t);
        yield null;
      }
      case DailyMaintenanceCmd c -> accountCommands.dailyMaintenance(now, t, restrictedAccounts());
      case CheckInvariantsCmd c -> checkInvariants();
      case HaltEngineCmd c -> {
        halt(c.reason());
        yield status(0);
      }
      case ResumeEngineCmd c -> {
        InvariantResult r = checkInvariants();
        if (r.ok()) {
          state = EngineState.RUNNING;
          haltReason = null;
        }
        yield status(0);
      }
    };
  }

  private Long reset(ResetAccountCmd c, Instant now, EngineTx t) {
    Long replayed = accountCommands.replayedReset(c.idem());
    if (replayed != null) {
      return replayed;
    }
    cancelAllOf(c.playerId(), CancelReason.ACCOUNT_RESET, c.actorPlayerId(), now, t);
    return accountCommands.reset(c.playerId(), c.startingCashOrNull(), c.memo(), c.idem(), c.actorPlayerId(), now, t);
  }

  private void cancelAllOf(long playerId, CancelReason reason, long actorPlayerId, Instant now, EngineTx t) {
    AccountRow acc = accountCommands.activeAccount(playerId);
    for (OrderRow r : orderStore.openByAccount(acc.id(), null)) {
      orders.cancelOpen(r, reason, actorPlayerId, now, t);
    }
  }

  private List<AccountRow> restrictedAccounts() {
    return jdbc.sql("SELECT id FROM account WHERE status = 'ACTIVE' AND restriction IN ('SETTLED_CASH_ONLY',"
            + "'LIQUIDATION_ONLY')")
        .query(Long.class).list().stream().map(id -> accountStore.byId(id).orElseThrow()).toList();
  }

  /** §9.4: pauses, resumes and expiries at a session edge, then {@code SessionChanged}. Never fills. */
  private Object transition(TransitionCmd c, Instant now, EngineTx t) {
    SessionState session = clock.session(now);
    for (OpenOrderIndex.Entry e : index.all()) {
      evaluator.lifecycle(e, session, now, t);
    }
    t.publish(new SessionChanged(session));
    return null;
  }

  private InvariantResult checkInvariants() {
    InvariantResult r = invariants.check();
    lastInvariants = r;
    if (!r.ok()) {
      log.error("Invariant failure: {}", r.failures());
      halt("Invariant failure: " + r.failures().get(0));
    }
    return r;
  }

  private void halt(String reason) {
    state = EngineState.HALTED;
    haltReason = reason;
    log.warn("Engine halted: {}", reason);
  }

  /** One transaction; after commit: index changes, then events. */
  private Object inTransaction(Function<EngineTx, Object> body) {
    EngineTx t = new EngineTx();
    Object result;
    try {
      result = tx.execute(status -> body.apply(t));
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
      log.error("Engine command failed", e);
      throw new ApiException(ErrorCode.INTERNAL_ERROR, null, e);
    }
    for (Runnable r : t.afterCommitActions()) {
      try {
        r.run();
      } catch (RuntimeException e) {
        log.warn("After-commit action failed", e);
      }
    }
    for (Object event : t.events()) {
      try {
        publisher.publishEvent(event);
      } catch (RuntimeException e) {
        log.warn("Event listener failed for {}", event.getClass().getSimpleName(), e);
      }
    }
    return result;
  }

  // ------------------------------------------------------------------------------------------- evaluation

  /** Called from market-data threads; must not block (§15.5). */
  public void markDirty(String symbol) {
    if (symbol != null && index.hasSymbol(symbol)) {
      dirty.add(symbol);
    }
  }

  /** Evaluates the symbols that had market updates since the last call. */
  public void evaluateDirty() {
    EngineThread.assertCurrent();
    if (state != EngineState.RUNNING || dirty.isEmpty()) {
      return;
    }
    Instant now = clock.now();
    SessionState session = clock.session(now);
    for (String symbol : Set.copyOf(dirty)) {
      dirty.remove(symbol);
      evaluateSymbol(symbol, now, session, true);
    }
  }

  /**
   * The 250 ms sweep (§12.2): activation, expiry, pause/resume at window edges and re-evaluation of orders not
   * evaluated in the last second.
   */
  public void sweep() {
    EngineThread.assertCurrent();
    if (state != EngineState.RUNNING) {
      return;
    }
    Instant now = clock.now();
    SessionState session = clock.session(now);
    for (String symbol : index.symbols()) {
      boolean due = index.on(symbol).stream().anyMatch(e -> needsAttention(e, now, session));
      if (due) {
        evaluateSymbol(symbol, now, session, false);
      }
    }
  }

  boolean needsAttention(OpenOrderIndex.Entry e, Instant now, SessionState session) {
    if (!now.isBefore(e.expiresAt)) {
      return true;
    }
    boolean open = evaluator.windowOpen(e.extendedHours, session, now);
    if (!open) {
      return e.status != OrderStatus.PENDING && e.waitReason != WaitReason.SESSION_CLOSED;
    }
    if (e.waitReason == WaitReason.SESSION_CLOSED) {
      return true;
    }
    if (now.isBefore(e.eligibleAt)) {
      return false;
    }
    return e.status == OrderStatus.PENDING || e.lastEvaluatedAt == null
        || !now.isBefore(e.lastEvaluatedAt.plus(RE_EVALUATE));
  }

  /** {@code evaluateSymbol(s)} of §12.2: drain prints, then every open order on {@code s} in evaluation order. */
  void evaluateSymbol(String symbol, Instant now, SessionState session, boolean fromMarketUpdate) {
    List<Print> drained;
    try {
      drained = marketData.drainPrints(symbol);
    } catch (RuntimeException e) {
      drained = List.of();
    }
    prints.append(symbol, drained, now);
    long cursorAfter = prints.lastSeq();
    for (OpenOrderIndex.Entry e : index.on(symbol)) {
      boolean restingRecently = e.hasRested && !e.reopenPending && e.lastEvaluatedAt != null
          && now.isBefore(e.lastEvaluatedAt.plus(RE_EVALUATE)) && now.isBefore(e.expiresAt)
          && e.waitReason != WaitReason.SESSION_CLOSED && SessionOrderMatrix.windowOpen(e.extendedHours, session.type());
      if (fromMarketUpdate && restingRecently) {
        continue;   // resting limits are evaluated at most once per second (§12.5)
      }
      List<PrintBuffer.Seq> since = prints.since(symbol, e.printCursor);
      try {
        inTransaction(t -> {
          evaluator.evaluate(e, session, now, since, cursorAfter, t);
          return null;
        });
      } catch (ApiException ex) {
        lastError = ex.code() + ": " + ex.detail();
        log.warn("Evaluating order {} failed: {}", e.publicId, ex.toString());
      }
    }
    if (!index.hasSymbol(symbol)) {
      prints.forget(symbol);
    }
  }

  // ------------------------------------------------------------------------------------------- status

  public EngineStatus status(int queueDepth) {
    return new EngineStatus(state, queueDepth, lastSeq, lastCommandAt, haltReason, lastError, lastInvariants);
  }

  public EngineState state() {
    return state;
  }

  public OpenOrderIndex index() {
    return index;
  }
}
