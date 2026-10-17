package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.Crypto;
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
import com.stocks.trading.corpactions.CorporateActionApplier;
import com.stocks.trading.engine.EngineCommand.ApplyCashTransfersCmd;
import com.stocks.trading.engine.EngineCommand.ApplyCorporateActionCmd;
import com.stocks.trading.engine.EngineCommand.CancelAllCmd;
import com.stocks.trading.engine.EngineCommand.CancelOrderCmd;
import com.stocks.trading.engine.EngineCommand.CancelOrderGroupCmd;
import com.stocks.trading.engine.EngineCommand.CheckInvariantsCmd;
import com.stocks.trading.engine.EngineCommand.DailyMaintenanceCmd;
import com.stocks.trading.engine.EngineCommand.HaltEngineCmd;
import com.stocks.trading.engine.EngineCommand.LiftRestrictionCmd;
import com.stocks.trading.engine.EngineCommand.OpenAccountCmd;
import com.stocks.trading.engine.EngineCommand.PayDividendsCmd;
import com.stocks.trading.engine.EngineCommand.PlaceOrderCmd;
import com.stocks.trading.engine.EngineCommand.PlaceOrderGroupCmd;
import com.stocks.trading.engine.EngineCommand.RecordAdminViewCmd;
import com.stocks.trading.engine.EngineCommand.ReplaceOrderCmd;
import com.stocks.trading.engine.EngineCommand.ReplaceOrderGroupCmd;
import com.stocks.trading.engine.EngineCommand.ResetAccountCmd;
import com.stocks.trading.engine.EngineCommand.ResolveCorporateActionCmd;
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
  /** Prefix of the halt reason after a failed bootstrap (shown on /admin/system). */
  static final String BOOTSTRAP_FAILED = "Bootstrap failed: ";

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
  private final CorporateActionApplier corporateActions;
  private final GroupOrderEntry groups;
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
                    MarketDataService marketData, OpenOrderIndex index, JdbcClient jdbc,
                    CorporateActionApplier corporateActions, GroupOrderEntry groups) {
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
    this.corporateActions = corporateActions;
    this.groups = groups;
  }

  // ------------------------------------------------------------------------------------------- bootstrap

  /**
   * §12.9 steps 4 and 6: invariants, then the open orders and their leases. Idempotent. If it throws (the database is
   * unreachable at boot, for example) the engine is HALTED with a {@code Bootstrap failed} reason, so orders are
   * refused instead of reserving buying power that nothing would ever release; {@link EngineRunner} retries with
   * backoff, and an admin resume retries it first.
   */
  public synchronized void bootstrap() {
    EngineThread.assertCurrent();
    if (bootstrapped) {
      return;
    }
    try {
      index.clear();
      for (OrderRow r : orderStore.open()) {
        orders.track(r);
      }
      InvariantResult result = invariants.check();
      lastInvariants = result;
      if (result.ok()) {
        state = EngineState.RUNNING;
        haltReason = null;
      } else {
        halt("Invariants failed at boot: " + result.failures().get(0));
      }
    } catch (RuntimeException e) {
      index.clear();
      lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
      halt(BOOTSTRAP_FAILED + lastError);
      throw e;
    }
    bootstrapped = true;
    log.info("Engine {} with {} open orders", state, index.size());
  }

  public boolean isBootstrapped() {
    return bootstrapped;
  }

  /**
   * What a process restart does to the engine (§12.9, §26 Recovery): every in-memory structure is dropped (open-order
   * index and leases, print buffer, dirty symbols, trigger and refresh state) and the open orders are reloaded from
   * the database. Nothing that happened while the engine was "down" is evaluated retroactively: prints and quotes
   * observed before the reload are never trigger or fill evidence. Recovery tests call it through
   * {@code DirectEngineExecutor.restart()}.
   */
  public synchronized void restart() {
    EngineThread.assertCurrent();
    for (OpenOrderIndex.Entry e : index.all()) {
      orders.untrack(e.id());
    }
    index.clear();
    prints.clear();
    dirty.clear();
    evaluator.forget();
    bootstrapped = false;
    bootstrap();
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
    if (state == EngineState.STARTING && !cmd.allowedWhileHalted()) {
      // not bootstrapped yet: nothing would ever activate, fill or expire the order
      throw ApiException.of(ErrorCode.ENGINE_HALTED, "The trading engine is starting.")
          .withHint("Retry in a few seconds.").withRetryAfter(5);
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
      case ReplaceOrderCmd c -> isGroupLeg(c) ? groups.replaceLeg(c, t) : orders.replace(c, t);
      case PlaceOrderGroupCmd c -> groups.place(c, t);
      case ReplaceOrderGroupCmd c -> groups.replaceGroup(c, t);
      case CancelOrderGroupCmd c -> groups.cancelGroup(c, t);
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
      case ApplyCorporateActionCmd c -> corporateActions.apply(c.actionId(), now, t);
      case ResolveCorporateActionCmd c -> corporateActions.resolve(c.actionId(), c.resolution(), c.oldRate(),
          c.newRate(), c.note(), c.actorPlayerId(), now, t);
      case PayDividendsCmd c -> corporateActions.payDividends(c.date(), now, t);
      case CheckInvariantsCmd c -> checkInvariants();
      case HaltEngineCmd c -> {
        halt(c.reason());
        yield status(0);
      }
      case ResumeEngineCmd c -> {
        if (!bootstrapped) {
          // a failed boot: reload the open orders first, which also runs the invariants and sets the state
          try {
            bootstrap();
          } catch (RuntimeException e) {
            throw ApiException.of(ErrorCode.ENGINE_HALTED, "The engine could not bootstrap: " + lastError)
                .withHint("Check the database and the logs, then resume again.").withRetryAfter(60);
          }
          if (state == EngineState.RUNNING) {
            t.afterCommit(() -> discardHaltEvidence(now));
          }
          yield status(0);
        }
        boolean wasHalted = state == EngineState.HALTED;
        InvariantResult r = checkInvariants();
        if (r.ok()) {
          state = EngineState.RUNNING;
          haltReason = null;
          if (wasHalted) {
            t.afterCommit(() -> discardHaltEvidence(now));
          }
        }
        yield status(0);
      }
    };
  }

  /** A replace of a group leg follows the group rules (§11.9). */
  private boolean isGroupLeg(ReplaceOrderCmd c) {
    return orderStore.byPublicId(c.accountId(), c.orderId()).map(OrderRow::inGroup).orElse(false);
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
      evaluator.lifecycle(e, sessionOf(e.symbol, session, now), now, t);   // crypto never pauses (§29)
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

  /**
   * HALTED → RUNNING is a reload for evidence purposes (§12.7, §12.9): nothing observed while the engine was halted
   * is ever trigger or fill evidence. The prints market data buffered meanwhile are drained and dropped, every order's
   * print cursor moves past them, armed stops are tracked again from now (their watermark and stop are kept), and
   * every order's next evaluation is an opening one (a current quote already through a stop still triggers, a
   * marketable limit fills at the touch, never at a print from the halt).
   */
  private void discardHaltEvidence(Instant now) {
    for (String symbol : index.symbols()) {
      try {
        marketData.drainPrints(symbol);
      } catch (RuntimeException ignored) {
        // market data unavailable: nothing buffered to drop
      }
      prints.forget(symbol);
    }
    long cursor = prints.lastSeq();
    for (OpenOrderIndex.Entry e : index.all()) {
      e.printCursor = cursor;
      e.reopenPending = true;
      if (e.trigger != null) {
        e.trigger = e.trigger.retrackedAt(now);
      }
    }
    log.info("Engine resumed: prints and quotes observed while halted are not evidence for {} open orders",
        index.size());
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
      evaluateSymbol(symbol, now, sessionOf(symbol, session, now), true);
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
    SessionState stocks = clock.session(now);
    for (String symbol : index.symbols()) {
      SessionState session = sessionOf(symbol, stocks, now);
      boolean due = index.on(symbol).stream().anyMatch(e -> needsAttention(e, now, session));
      if (due) {
        evaluateSymbol(symbol, now, session, false);
      }
    }
  }

  /** The session {@code symbol} trades in: the NYSE session for stocks, always REGULAR for crypto pairs (§29). */
  private SessionState sessionOf(String symbol, SessionState stocks, Instant now) {
    return Crypto.isCrypto(symbol) ? clock.session(symbol, now) : stocks;
  }

  boolean needsAttention(OpenOrderIndex.Entry e, Instant now, SessionState session) {
    if (!now.isBefore(e.expiresAt)) {
      return true;
    }
    if (e.cancelEffectiveAt != null && !now.isBefore(e.cancelEffectiveAt)) {
      return true;                                  // a requested cancel takes effect (§11.10)
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
