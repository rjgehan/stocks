package com.stocks.realtime;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.NotificationKind;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.CashTransferPosted;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.events.ViolationIssued;
import com.stocks.contract.realtime.dto.OrderUpdateDto;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns the engine's after-commit domain events (§5.3) into user pushes and notifications (§19):
 * <ul>
 *   <li>{@code OrderChanged} → {@code /user/queue/orders} {@code OrderUpdateDto {event, order, execution}} (the
 *       execution is the order's latest one on {@code FILL}/{@code PARTIAL_FILL}); notifications for fills, partial
 *       fills, expiries and cancellations the player did not ask for;</li>
 *   <li>{@code AccountChanged}, {@code CashTransferPosted}, {@code ViolationIssued} and every {@code OrderChanged} →
 *       {@code /user/queue/account} {@code Account}, coalesced per account (one read for a burst of events);</li>
 *   <li>{@code CashTransferPosted} (deposits, withdrawals, adjustments), {@code ViolationIssued},
 *       {@code AccountChanged(reason=RESET)} and {@code PlayerStatusChanged} (freeze/unfreeze) → notifications.</li>
 * </ul>
 * The listeners run on the publisher's (engine) thread and only enqueue: the work runs on one {@code realtime-events}
 * thread, in event order, so a player sees ACCEPTED before FILL. The queue is bounded ({@value #QUEUE_CAPACITY});
 * overflow is dropped and logged (delivery is best effort and clients resync on reconnect, §19; notifications are
 * idempotent by {@code source_ref}). Pushes skip players who are not connected; notifications are written either way.
 */
@Component
public class UserEventPublisher implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);

  static final int QUEUE_CAPACITY = 10_000;
  /** Cancellations the player caused themselves: no notification (the UI already showed the result). */
  private static final Set<String> SELF_CANCEL = Set.of(CancelReason.USER.name(), CancelReason.REPLACED_BY_USER.name());
  private static final Set<OrderEventType> WITH_DETAIL = EnumSet.of(OrderEventType.FILL, OrderEventType.PARTIAL_FILL,
      OrderEventType.CANCELLED, OrderEventType.EXPIRED);

  private final OrderQueries orders;
  private final AccountQueries accounts;
  private final NotificationService notifications;
  private final UserPush push;
  private final Executor executor;
  private final ExecutorService ownedExecutor;
  /** Accounts with a queued push ({@code accountId → playerId}); a burst of events causes one read. */
  private final Map<Long, Long> pendingAccounts = new ConcurrentHashMap<>();

  @Autowired
  public UserEventPublisher(OrderQueries orders, AccountQueries accounts, NotificationService notifications,
                            UserPush push) {
    this(orders, accounts, notifications, push, serialExecutor());
  }

  /** Tests pass {@code Runnable::run} to handle events synchronously. */
  UserEventPublisher(OrderQueries orders, AccountQueries accounts, NotificationService notifications, UserPush push,
                     Executor executor) {
    this.orders = orders;
    this.accounts = accounts;
    this.notifications = notifications;
    this.push = push;
    this.executor = executor;
    this.ownedExecutor = executor instanceof ExecutorService es ? es : null;
  }

  private static ExecutorService serialExecutor() {
    return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
        Thread.ofPlatform().daemon().name("realtime-events").factory());
  }

  // ------------------------------------------------------------------------------------------- listeners

  @EventListener
  public void on(OrderChanged e) {
    submit(() -> handle(e));
  }

  @EventListener
  public void on(AccountChanged e) {
    submit(() -> handle(e));
  }

  @EventListener
  public void on(CashTransferPosted e) {
    submit(() -> handle(e));
  }

  @EventListener
  public void on(ViolationIssued e) {
    submit(() -> handle(e));
  }

  @EventListener
  public void on(PlayerStatusChanged e) {
    submit(() -> handle(e));
  }

  private void submit(Runnable work) {
    try {
      executor.execute(() -> {
        try {
          work.run();
        } catch (RuntimeException ex) {
          log.warn("Realtime event handling failed: {}", ex.toString(), ex);
        }
      });
    } catch (RejectedExecutionException ex) {
      log.warn("Realtime event dropped (queue full or shutting down)");
    }
  }

  // ------------------------------------------------------------------------------------------- handlers

  void handle(OrderChanged e) {
    boolean connected = push.isConnected(e.playerId());
    boolean notify = notifies(e);
    if (connected || notify) {
      boolean detail = WITH_DETAIL.contains(e.eventType());
      Optional<OrderDto> order = lookup(() -> orders.byPublicId(e.accountId(), e.orderId(), detail));
      if (order.isPresent()) {
        OrderDto o = order.get();
        ExecutionDto execution = e.eventType() == OrderEventType.FILL || e.eventType() == OrderEventType.PARTIAL_FILL
            ? latestExecution(o) : null;
        if (connected) {
          push.toPlayer(e.playerId(), StompDestinations.ORDERS_QUEUE,
              new OrderUpdateDto(e.eventType(), withoutDetail(o), execution));
        }
        if (notify) {
          notifyOrder(e, o, execution);
        }
      }
    }
    queueAccountPush(e.accountId(), e.playerId());
  }

  void handle(AccountChanged e) {
    if ("RESET".equalsIgnoreCase(e.reason())) {
      // A reset publishes AccountChanged(RESET) for the closed run and for the new one, with the same instant:
      // keying by player and instant notifies once per reset.
      notifications.notify(e.playerId(), NotificationKind.ACCOUNT_RESET, "Your account was reset",
          "A new run started with fresh cash. Your leaderboard history is kept: a reset never erases a loss.",
          Map.of("reason", "RESET"), "RESET:" + e.playerId() + ":" + e.at().toEpochMilli());
    }
    queueAccountPush(e.accountId(), e.playerId());
  }

  void handle(CashTransferPosted e) {
    NotificationKind kind = switch (e.type()) {
      case DEPOSIT -> NotificationKind.DEPOSIT;
      case WITHDRAWAL -> NotificationKind.WITHDRAWAL;
      case ADJUSTMENT -> NotificationKind.ADJUSTMENT;
      case INITIAL -> null;
    };
    if (kind != null) {
      BigDecimal amount = e.amount() == null ? BigDecimal.ZERO : e.amount();
      String title = switch (e.type()) {
        case DEPOSIT -> "Deposit: +" + money(amount.abs());
        case WITHDRAWAL -> "Withdrawal: −" + money(amount.abs());
        default -> "Cash adjustment: " + signedMoney(amount);
      };
      String memo = e.memo() == null || e.memo().isBlank() ? null : e.memo().strip();
      String body = (e.type() == TransferType.WITHDRAWAL ? "The league admin withdrew cash from your account."
          : e.type() == TransferType.DEPOSIT ? "The league admin added cash to your account. It is settled and "
              + "available now, and deposits never change your return on the leaderboard."
          : "The league admin adjusted your cash balance.")
          + (memo == null ? "" : " Memo: " + memo);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("transferId", e.transferId());
      payload.put("batchId", e.batchId());
      payload.put("type", e.type().name());
      payload.put("amount", amount);
      notifications.notify(e.playerId(), kind, title, body, payload, "XFER:" + e.transferId());
    }
    queueAccountPush(e.accountId(), e.playerId());
  }

  void handle(ViolationIssued e) {
    AccountRestriction after = e.restrictionAfter() == null ? AccountRestriction.NONE : e.restrictionAfter();
    String body = "You sold shares that were bought with unsettled funds before those funds settled (a good-faith "
        + "violation). Violations expire after one year."
        + switch (after) {
          case SETTLED_CASH_ONLY -> " Your account is now restricted to settled cash for 90 days.";
          case LIQUIDATION_ONLY -> " Your account is now liquidation-only (sells only) for 90 days.";
          case FROZEN -> " Your account is frozen.";
          case NONE -> " Wait for settlement (T+1) before selling shares bought with unsettled proceeds.";
        };
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("violationId", e.violationId());
    payload.put("restriction", after.name());
    notifications.notify(e.playerId(), NotificationKind.GFV_ISSUED, "Good-faith violation", body, payload,
        "VIO:" + e.violationId());
    queueAccountPush(e.accountId(), e.playerId());
  }

  void handle(PlayerStatusChanged e) {
    String title;
    String body;
    if (e.status() == PlayerStatus.FROZEN) {
      title = "Your account is frozen";
      body = "The league admin froze your account: open orders were cancelled and new orders are refused. "
          + "Your positions are kept.";
    } else if (e.status() == PlayerStatus.ACTIVE) {
      title = "Your account is active again";
      body = "The league admin lifted the freeze. You can trade again.";
    } else {
      return;   // DISABLED players cannot sign in to read it
    }
    notifications.notify(e.playerId(), NotificationKind.RESTRICTION_CHANGED, title, body,
        Map.of("status", e.status().name()), "STATUS:" + e.status().name() + ":" + e.at().toEpochMilli());
  }

  // ------------------------------------------------------------------------------------------- orders

  private static boolean notifies(OrderChanged e) {
    return switch (e.eventType()) {
      case FILL, PARTIAL_FILL, EXPIRED, CANCELLED -> true;
      default -> false;
    };
  }

  private void notifyOrder(OrderChanged e, OrderDto o, ExecutionDto execution) {
    NotificationKind kind;
    String title;
    String body;
    String what = describe(o);
    switch (e.eventType()) {
      case FILL -> {
        kind = NotificationKind.FILL;
        title = (o.side() == Side.BUY ? "Bought " : "Sold ") + qty(o.filledQty()) + " " + o.symbol();
        body = "Your " + what + " filled" + (o.avgFillPrice() == null ? "." : " at an average price of "
            + price(o.avgFillPrice()) + ".");
      }
      case PARTIAL_FILL -> {
        kind = NotificationKind.PARTIAL_FILL;
        title = "Partially filled: " + (o.side() == Side.BUY ? "buy " : "sell ") + o.symbol();
        body = qty(o.filledQty()) + (o.qty() == null ? "" : " of " + qty(o.qty())) + " shares of your " + what
            + " filled" + (execution == null ? "" : ", the last at " + price(execution.price())) + ". The rest is "
            + "still working.";
      }
      case EXPIRED -> {
        kind = NotificationKind.ORDER_EXPIRED;
        title = "Order expired: " + (o.side() == Side.BUY ? "buy " : "sell ") + o.symbol();
        body = "Your " + what + " expired at the end of its " + o.timeInForce() + " session"
            + (o.filledQty() != null && o.filledQty().signum() > 0 ? " after filling " + qty(o.filledQty()) : "")
            + ".";
      }
      case CANCELLED -> {
        if (o.statusReason() != null && SELF_CANCEL.contains(o.statusReason())) {
          return;
        }
        kind = NotificationKind.ORDER_CANCELLED;
        title = "Order cancelled: " + (o.side() == Side.BUY ? "buy " : "sell ") + o.symbol();
        body = "Your " + what + " was cancelled: " + cancelReason(o.statusReason()) + ".";
      }
      default -> {
        return;
      }
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", o.orderId());
    payload.put("symbol", o.symbol());
    payload.put("side", o.side() == null ? null : o.side().name());
    payload.put("status", o.status() == null ? null : o.status().name());
    if (execution != null) {
      payload.put("executionId", execution.executionId());
    }
    notifications.notify(e.playerId(), kind, title, body, payload, orderSourceRef(e, o));
  }

  /** {@code ORD:<ord>:<event seq>} (§8.1); without the timeline, the event type and instant stand in for the seq. */
  static String orderSourceRef(OrderChanged e, OrderDto o) {
    if (o.events() != null) {
      Optional<OrderEventDto> event = o.events().stream().filter(ev -> ev.type() == e.eventType())
          .max(Comparator.comparingInt(OrderEventDto::seq));
      if (event.isPresent()) {
        return "ORD:" + e.orderId() + ":" + event.get().seq();
      }
    }
    return "ORD:" + e.orderId() + ":" + e.eventType().name() + ":" + e.at().toEpochMilli();
  }

  private static ExecutionDto latestExecution(OrderDto o) {
    if (o.executions() == null || o.executions().isEmpty()) {
      return null;
    }
    return o.executions().stream()
        .max(Comparator.comparing(ExecutionDto::executedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
        .orElse(null);
  }

  /** The §19 payload carries the order without its timeline (as {@code GET /orders} does). */
  static OrderDto withoutDetail(OrderDto o) {
    if (o.executions() == null && o.events() == null) {
      return o;
    }
    return o.withDetail(null, null);
  }

  // ------------------------------------------------------------------------------------------- account pushes

  private void queueAccountPush(long accountId, long playerId) {
    if (!push.isConnected(playerId)) {
      return;
    }
    if (pendingAccounts.putIfAbsent(accountId, playerId) == null) {
      submit(() -> pushAccount(accountId));
    }
  }

  private void pushAccount(long accountId) {
    Long playerId = pendingAccounts.remove(accountId);
    if (playerId == null || !push.isConnected(playerId)) {
      return;
    }
    lookup(() -> Optional.ofNullable(accounts.account(accountId)))
        .ifPresent(a -> push.toPlayer(playerId, StompDestinations.ACCOUNT_QUEUE, a));
  }

  /** Pushes the player's live {@code Account} now (the periodic marks push, {@link AccountTopic}). */
  void pushAccountOf(long playerId) {
    lookup(() -> Optional.of(accounts.activeAccountId(playerId)))
        .flatMap(id -> lookup(() -> Optional.<AccountDto>ofNullable(accounts.account(id))))
        .ifPresent(a -> push.toPlayer(playerId, StompDestinations.ACCOUNT_QUEUE, a));
  }

  /** Runs a contract lookup; "not there" and "not implemented yet" (Day-0 stubs) are empty, not errors. */
  private static <T> Optional<T> lookup(java.util.function.Supplier<Optional<T>> query) {
    try {
      return query.get();
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED || e.code().status() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  // ------------------------------------------------------------------------------------------- text

  private static String describe(OrderDto o) {
    StringBuilder s = new StringBuilder();
    s.append(o.type() == null ? "order" : o.type().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " order");
    s.append(" to ").append(o.side() == Side.SELL ? "sell " : "buy ");
    if (o.qty() != null) {
      s.append(qty(o.qty())).append(' ').append(o.symbol());
    } else if (o.notional() != null) {
      s.append(money(o.notional())).append(" of ").append(o.symbol());
    } else {
      s.append(o.symbol());
    }
    if (o.limitPrice() != null && (o.type() == OrderType.LIMIT || o.type() == OrderType.STOP_LIMIT)) {
      s.append(" at ").append(price(o.limitPrice()));
    }
    return s.toString();
  }

  private static String cancelReason(String reason) {
    if (reason == null) {
      return "cancelled by the system";
    }
    try {
      return switch (CancelReason.valueOf(reason)) {
        case ADMIN -> "cancelled by the league admin";
        case PLAYER_FROZEN -> "your account was frozen";
        case PLAYER_DISABLED -> "your account was disabled";
        case ACCOUNT_RESET -> "your account was reset";
        case CORPORATE_ACTION -> "a corporate action (split or symbol change) affected the stock";
        case INSUFFICIENT_BUYING_POWER_AT_EXECUTION -> "not enough buying power when it executed";
        case DUST -> "the quantity rounded below the minimum (0.00001 shares)";
        case OCO_FILLED -> "the other order of its one-cancels-other pair executed";
        case PARENT_NOT_FILLED -> "its entry order ended without a fill";
        case USER, REPLACED_BY_USER -> "you cancelled it";
      };
    } catch (IllegalArgumentException e) {
      return reason.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
  }

  static String qty(BigDecimal q) {
    return q == null ? "?" : q.stripTrailingZeros().toPlainString();
  }

  static String price(BigDecimal p) {
    if (p == null) {
      return "?";
    }
    BigDecimal abs = p.abs();
    int scale = abs.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
    return "$" + String.format(Locale.US, "%,." + scale + "f", p.setScale(scale, RoundingMode.HALF_UP));
  }

  static String money(BigDecimal m) {
    return "$" + String.format(Locale.US, "%,.2f", m.setScale(2, RoundingMode.HALF_UP));
  }

  static String signedMoney(BigDecimal m) {
    return (m.signum() < 0 ? "−" : "+") + money(m.abs());
  }

  @Override
  public void destroy() {
    if (ownedExecutor != null) {
      ownedExecutor.shutdown();
      try {
        ownedExecutor.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** The restriction label shown to players. */
  static String restriction(AccountRestriction r) {
    return r == null ? "NONE" : r.name();
  }

  List<Long> pendingAccountPushes() {
    return List.copyOf(pendingAccounts.keySet());
  }
}
