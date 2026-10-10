package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Qty;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.engine.EngineCommand.CancelAllCmd;
import com.stocks.trading.engine.EngineCommand.CancelOrderCmd;
import com.stocks.trading.engine.EngineCommand.PlaceOrderCmd;
import com.stocks.trading.engine.EngineCommand.ReplaceOrderCmd;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.order.NormalizedOrder;
import com.stocks.trading.order.OrderEvaluation;
import com.stocks.trading.order.OrderOrigin;
import com.stocks.trading.order.OrderReplay;
import com.stocks.trading.order.OrderStateMachine;
import com.stocks.trading.order.OrderTypes;
import com.stocks.trading.order.RequestHasher;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.order.mapper.OrderDtoMapper;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Order entry on the engine thread: place, replace, cancel and cancel-all (§11). */
@Component
class OrderHandler {

  private final OrderStore orders;
  private final AccountStore accounts;
  private final OrderWriter writer;
  private final OrderEvaluation evaluation;
  private final LatencyModel latency;
  private final TradingClock clock;
  private final PublicId publicIds;
  private final MarketDataService marketData;
  private final OrderDtoMapper mapper;
  private final OpenOrderIndex index;
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final OrderReplay replays;

  OrderHandler(OrderStore orders, AccountStore accounts, OrderWriter writer, OrderEvaluation evaluation,
               LatencyModel latency, TradingClock clock, PublicId publicIds, MarketDataService marketData,
               OrderDtoMapper mapper, OpenOrderIndex index, JdbcClient jdbc, JsonMapper json, OrderReplay replays) {
    this.orders = orders;
    this.accounts = accounts;
    this.writer = writer;
    this.evaluation = evaluation;
    this.latency = latency;
    this.clock = clock;
    this.publicIds = publicIds;
    this.marketData = marketData;
    this.mapper = mapper;
    this.index = index;
    this.jdbc = jdbc;
    this.json = json;
    this.replays = replays;
  }

  // ------------------------------------------------------------------------------------------- place

  PlaceOrderResponse place(PlaceOrderCmd cmd, EngineTx tx) {
    NormalizedOrder o = cmd.order();
    OrderReplay.requireClientOrderId(o.clientOrderId());
    // check 6, authoritative (§11.7)
    var existing = orders.byClientOrderId(cmd.accountId(), o.clientOrderId());
    if (existing.isPresent()) {
      return replays.replay(existing.get(), cmd.requestHash());
    }
    AccountRow acc = activeAccount(cmd.accountId());
    return accept(acc, cmd.origin(), o, cmd.requestHash(), cmd.receivedAt(), null, tx);
  }

  /** Checks 7–27 against the engine's state, then the PENDING order, its events and (after commit) its lease. */
  private PlaceOrderResponse accept(AccountRow acc, OrderOrigin origin, NormalizedOrder o, String requestHash,
                                    Instant receivedAt, OrderRow replacing, EngineTx tx) {
    Instant now = clock.now();
    OrderEvaluation.Evaluated ev = evaluation.evaluate(acc, origin.kind(), o, now);
    ensureAssetRow(ev.market().asset(), now);

    String publicId = publicIds.next(Prefix.ORDER);
    Instant eligibleAt = ev.acceptance().eligibleBase().plus(latency.latency(publicId, 0));
    boolean rationalePublic = origin.kind() == PlayerKind.AI || o.shareNote();
    BigDecimal qty = o.qty() == null ? null : Qty.normalize(o.qty());
    BigDecimal notional = o.notional() == null ? null : Money.require("notional", o.notional());
    BigDecimal limit = o.limitPrice() == null ? null : Prices.normalize(o.limitPrice());
    BigDecimal stop = o.stopPrice() == null ? null : Prices.normalize(o.stopPrice());
    BigDecimal trailAmount = o.trailAmount() == null ? null : Prices.normalize(o.trailAmount());
    BigDecimal trailPercent = o.trailPercent() == null ? null : o.trailPercent().setScale(4, RoundingMode.UNNECESSARY);
    long id = writer.insert(new OrderWriter.NewOrder(publicId, acc.id(), acc.playerId(), o.clientOrderId(),
        requestHash, o.symbol(), o.side(), o.type(), qty, notional, o.fractional(), limit, o.timeInForce(),
        o.extendedHours(), ev.acceptance().waitReason(), ev.result().reservedCash(), ev.result().reservedQty(),
        ev.expiry().sessionDate(), receivedAt, now, eligibleAt, ev.expiry().expiresAt(),
        replacing == null ? null : replacing.id(), origin.channel(), origin.apiKeyId(), origin.oauthClientId(),
        origin.mcpClient(), origin.clientIp(), o.rationale(), rationalePublic, stop, trailAmount, trailPercent));
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("channel", origin.channel().name());
    detail.put("eligibleAt", eligibleAt.toString());
    if (replacing != null) {
      detail.put("replaces", replacing.publicId());
    }
    writer.event(id, OrderEventType.ACCEPTED, OrderStatus.PENDING, now, json.writeValueAsString(detail));
    if (ev.acceptance().queued()) {
      writer.event(id, OrderEventType.QUEUED, OrderStatus.PENDING, now,
          json.writeValueAsString(Map.of("waitReason", ev.acceptance().waitReason().name())));
    }
    OrderRow row = orders.byId(id).orElseThrow();
    tx.afterCommit(() -> track(row));
    tx.publish(new OrderChanged(acc.id(), acc.playerId(), publicId, OrderEventType.ACCEPTED, OrderStatus.PENDING, now));
    tx.publish(new AccountChanged(acc.id(), acc.playerId(), "ORDER_ACCEPTED", now));
    return new PlaceOrderResponse(mapper.toDto(row), ev.result().estimate(), ev.result().buyingPowerBefore(),
        ev.result().buyingPowerAfter(), ev.result().warnings(), false);
  }

  /**
   * After commit: index the order and lease its symbol so market data keeps it fresh (§12.2, §15.5): an armed stop
   * holds an {@code ORDER_TRIGGER} lease (it needs prints), everything else {@code ORDER_RESTING}.
   */
  void track(OrderRow row) {
    OpenOrderIndex.Entry e = index.put(row);
    if (OrderTypes.isStop(row.type()) && e.trigger == null) {
      e.trigger = TriggerEvaluator.stateFor(row, clock.now());
    }
    if (e.lease == null) {
      LeasePriority priority = OrderTypes.isStop(row.type()) && !row.triggered() ? LeasePriority.ORDER_TRIGGER
          : LeasePriority.ORDER_RESTING;
      try {
        e.lease = marketData.lease(row.symbol(), priority, "order:" + row.publicId());
      } catch (RuntimeException ignored) {
        e.lease = null;   // market data unavailable: evaluation requests refreshes instead
      }
    }
  }

  /** After commit: forget a terminal order and release its lease. */
  void untrack(long orderId) {
    OpenOrderIndex.Entry e = index.remove(orderId);
    if (e != null && e.lease != null) {
      try {
        e.lease.close();
      } catch (RuntimeException ignored) {
        // best effort
      }
    }
  }

  /**
   * {@code trade_order.symbol} references {@code asset(symbol)}. WS2 keeps that table in sync; if a symbol that market
   * data knows is missing there (a fresh database, a new listing), the engine inserts the row from the market-data
   * asset rather than failing the order on the foreign key.
   */
  void ensureAssetRow(Asset a, Instant now) {
    if (a == null) {
      return;
    }
    jdbc.sql("""
            INSERT INTO asset (symbol, name, exchange, asset_type, tradable, fractionable, shortable, easy_to_borrow,
                               marginable, liquidity_tier, status, source, updated_at)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'manual', ?
             WHERE NOT EXISTS (SELECT 1 FROM asset WHERE symbol = ?)""")
        .params(a.symbol(), a.name() == null ? a.symbol() : a.name(), new SqlParameterValue(Types.VARCHAR, a.exchange()),
            a.type() == null ? "UNKNOWN" : a.type().name(), a.tradable(), a.fractionable(), a.shortable(),
            a.easyToBorrow(), a.marginable(), a.tier() == null ? "SMALL" : a.tier().name(),
            a.active() ? "ACTIVE" : "INACTIVE", Jdbc.ts(now), a.symbol())
        .update();
  }

  // ------------------------------------------------------------------------------------------- replace

  PlaceOrderResponse replace(ReplaceOrderCmd cmd, EngineTx tx) {
    ReplaceOrderRequest req = cmd.request();
    OrderRow old = orders.byPublicId(cmd.accountId(), cmd.orderId())
        .orElseThrow(() -> notFound(cmd.orderId()));
    if (old.fractional()) {
      throw ApiException.of(ErrorCode.FRACTIONAL_NOT_REPLACEABLE,
              "Fractional and dollar orders cannot be replaced; cancel and resubmit.")
          .withField("orderId", ErrorCode.FRACTIONAL_NOT_REPLACEABLE, "Cancel instead");
    }
    String newClientId = req.clientOrderId() == null ? null : req.clientOrderId().trim();
    OrderReplay.requireClientOrderId(newClientId);
    var existing = orders.byClientOrderId(cmd.accountId(), newClientId);
    NormalizedOrder replacement = replacementOf(old, req, newClientId);
    String hash = RequestHasher.hash(replacement);
    if (existing.isPresent()) {
      return replays.replay(existing.get(), hash);
    }
    if (!(old.status() == OrderStatus.PENDING || old.status() == OrderStatus.WORKING)
        || old.filledQty().signum() > 0) {
      throw ApiException.of(ErrorCode.ORDER_NOT_REPLACEABLE, "Order " + old.publicId() + " is " + old.status()
              + (old.filledQty().signum() > 0 ? " with fills" : "") + " and cannot be replaced.")
          .withHint("Only unfilled PENDING or WORKING orders can be replaced.");
    }
    AccountRow acc = activeAccount(cmd.accountId());
    // One transaction: the old order is REPLACED first, so its hold is credited back to the buying power the new
    // order is validated against; if validation fails the whole command rolls back and nothing changes (§11.6).
    Instant now = clock.now();
    writer.finish(old.id(), OrderStateMachine.transition(old.status(), OrderStatus.REPLACED),
        CancelReason.REPLACED_BY_USER.name(), now);
    PlaceOrderResponse response = accept(acc, cmd.origin(), replacement, hash, cmd.receivedAt(), old, tx);
    long newId = orders.byPublicId(response.order().orderId()).orElseThrow().id();
    writer.linkReplacement(old.id(), newId);
    writer.event(old.id(), OrderEventType.REPLACED, OrderStatus.REPLACED, now,
        json.writeValueAsString(Map.of("replacedBy", response.order().orderId())));
    tx.afterCommit(() -> untrack(old.id()));
    tx.publish(new OrderChanged(acc.id(), acc.playerId(), old.publicId(), OrderEventType.REPLACED,
        OrderStatus.REPLACED, now));
    OrderRow fresh = orders.byId(newId).orElseThrow();
    return new PlaceOrderResponse(mapper.toDto(fresh), response.estimate(), response.buyingPowerBefore(),
        response.buyingPowerAfter(), response.warnings(), false);
  }

  /**
   * The new order of a replace: immutable symbol, side and type; null fields keep the old value (§11.6). A trailing
   * stop's trail is replaced as a pair (sending one of {@code trailAmount}/{@code trailPercent} drops the other), and
   * its computed stop is never carried over: the new order starts a fresh watermark (§12.7).
   */
  static NormalizedOrder replacementOf(OrderRow old, ReplaceOrderRequest req, String clientOrderId) {
    TimeInForce tif = req.timeInForce() != null ? req.timeInForce() : old.timeInForce();
    boolean ext = req.extendedHours() != null ? req.extendedHours() : old.extendedHours();
    BigDecimal stop = req.stopPrice() != null ? req.stopPrice()
        : OrderTypes.usesStop(old.type()) ? old.stopPrice() : null;
    boolean newTrail = req.trailAmount() != null || req.trailPercent() != null;
    BigDecimal trailAmount = newTrail ? req.trailAmount() : old.trailAmount();
    BigDecimal trailPercent = newTrail ? req.trailPercent() : old.trailPercent();
    return new NormalizedOrder(clientOrderId, old.symbol(), old.side(), old.type(),
        req.qty() != null ? req.qty() : old.qty(), null,
        req.limitPrice() != null ? req.limitPrice() : old.limitPrice(), stop, trailAmount, trailPercent, tif, ext,
        req.rationale() != null ? req.rationale().trim() : old.rationale(), old.rationalePublic(), Set.of());
  }

  // ------------------------------------------------------------------------------------------- cancel

  OrderDto cancel(CancelOrderCmd cmd, EngineTx tx) {
    OrderRow r = (cmd.accountIdOrNull() == null ? orders.byPublicId(cmd.orderId())
        : orders.byPublicId(cmd.accountIdOrNull(), cmd.orderId()))
        .orElseThrow(() -> notFound(cmd.orderId()));
    if (r.status() == OrderStatus.CANCELLED) {
      return mapper.toDto(r);
    }
    if (!r.isOpen()) {
      throw ApiException.of(ErrorCode.ORDER_NOT_CANCELLABLE, "Order " + r.publicId() + " is " + r.status()
          + " and cannot be cancelled.");
    }
    cancelOpen(r, cmd.reason(), cmd.actorPlayerId(), clock.now(), tx);
    return mapper.toDto(orders.byId(r.id()).orElseThrow());
  }

  List<OrderDto> cancelAll(CancelAllCmd cmd, EngineTx tx) {
    List<OrderDto> out = new ArrayList<>();
    Instant now = clock.now();
    for (OrderRow r : orders.openByAccount(cmd.accountId(), cmd.symbolOrNull())) {
      cancelOpen(r, cmd.reason(), cmd.actorPlayerId(), now, tx);
      out.add(mapper.toDto(orders.byId(r.id()).orElseThrow()));
    }
    return out;
  }

  /** Cancels one open order and releases its holds (§11.6); used by user, admin, freeze and reset. */
  void cancelOpen(OrderRow r, CancelReason reason, long actorPlayerId, Instant now, EngineTx tx) {
    OrderStateMachine.transition(r.status(), OrderStatus.CANCELLED);
    writer.finish(r.id(), OrderStatus.CANCELLED, reason.name(), now);
    writer.event(r.id(), OrderEventType.CANCELLED, OrderStatus.CANCELLED, now,
        json.writeValueAsString(Map.of("reason", reason.name(), "by", actorPlayerId == r.playerId() ? "SELF"
            : actorPlayerId <= 0 ? "SYSTEM" : "ADMIN")));
    tx.afterCommit(() -> untrack(r.id()));
    tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.CANCELLED,
        OrderStatus.CANCELLED, now));
    tx.publish(new AccountChanged(r.accountId(), r.playerId(), "ORDER_CANCELLED", now));
  }

  // ------------------------------------------------------------------------------------------- helpers

  private AccountRow activeAccount(long accountId) {
    AccountRow acc = accounts.byId(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "No such account."));
    if (!acc.isActive()) {
      throw ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "The account is closed (reset); use the active run.");
    }
    return acc;
  }

  static ApiException notFound(String orderId) {
    return ApiException.of(ErrorCode.ORDER_NOT_FOUND, "No order " + orderId + ".");
  }
}
