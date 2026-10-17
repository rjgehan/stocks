package com.stocks.trading.order;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.time.Et;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Order entry on the request thread (§11.2): {@link OrderGate} (checks 1–6, one write token), then one engine command
 * whose own checks are authoritative. Synchronous rejections are not persisted (D15): they answer 4xx and write an
 * {@code ORDER/ORDER_REJECT} audit row; accepted orders, replaces and cancels are audited too (§17.7).
 */
@Service
public class OrderServiceImpl implements OrderService {

  private final OrderGate gate;
  private final EngineCalls engine;
  private final AccountQueries accountQueries;
  private final AccountStore accounts;
  private final OrderStore orders;
  private final OrderReplay replays;
  private final OrderEvaluation evaluation;
  private final OrderContext context;
  private final TradingClock clock;
  private final AuditService audit;
  private final GroupOrderService groups;

  public OrderServiceImpl(OrderGate gate, EngineCalls engine, AccountQueries accountQueries, AccountStore accounts,
                          OrderStore orders, OrderReplay replays, OrderEvaluation evaluation, OrderContext context,
                          TradingClock clock, AuditService audit, GroupOrderService groups) {
    this.gate = gate;
    this.engine = engine;
    this.accountQueries = accountQueries;
    this.accounts = accounts;
    this.orders = orders;
    this.replays = replays;
    this.evaluation = evaluation;
    this.context = context;
    this.clock = clock;
    this.audit = audit;
    this.groups = groups;
  }

  @Override
  public PlaceOrderResponse place(PlayerPrincipal who, PlaceOrderRequest req, RequestMeta meta) {
    NormalizedOrder o = NormalizedOrder.of(req);
    Instant received = clock.now();
    try {
      gate.preflight(who, true);
      OrderReplay.requireClientOrderId(o.clientOrderId());
      long accountId = accountQueries.activeAccountId(who.playerId());
      String hash = RequestHasher.hash(o);
      Optional<OrderRow> existing = orders.byClientOrderId(accountId, o.clientOrderId());
      if (existing.isPresent()) {
        return replays.replay(existing.get(), hash);           // fast path: free (§11.2 check 6)
      }
      gate.chargeWrite(who.playerId());
      PlaceOrderResponse r = engine.call(new EngineCommand.PlaceOrderCmd(accountId, OrderOrigin.of(who, meta), o, hash,
          received));
      if (r.replayed()) {
        gate.refundWrite(who.playerId());
      } else {
        audit(who, meta, "ORDER_PLACE", AuditOutcome.OK, r.order().orderId(), null, o);
      }
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_REJECT", outcome(e), null, e, o);
      throw e;
    }
  }

  @Override
  public PreviewDto preview(PlayerPrincipal who, PlaceOrderRequest req) {
    NormalizedOrder o = NormalizedOrder.of(req);
    BigDecimal bpBefore = Money.ZERO;
    String sessionNote = null;
    try {
      gate.preflight(who, false);
      if (o.clientOrderId() != null) {
        OrderReplay.requireClientOrderId(o.clientOrderId());
      }
      AccountRow acc = activeAccount(who.playerId());
      Instant now = clock.now();
      bpBefore = context.balances(acc, Et.date(now)).buyingPower();
      sessionNote = sessionNote(o, now);   // shown even when a later check fails
      OrderEvaluation.Evaluated ev = evaluation.evaluate(acc, who.kind(), o, now, true);
      sessionNote = SessionOrderMatrix.sessionNote(ev.acceptance(), now);
      return new PreviewDto(true, List.of(), ev.result().warnings(), ev.result().estimate(),
          ev.result().buyingPowerBefore(), ev.result().buyingPowerAfter(), sessionNote);
    } catch (ApiException e) {
      String field = e.fields().isEmpty() ? null : e.fields().get(0).field();
      return new PreviewDto(false, List.of(new PreviewDto.PreviewErrorDto(e.code().name(), field, e.detail(),
          e.hint())), List.of(), null, bpBefore, null, sessionNote);
    }
  }

  @Override
  public PlaceOrderResponse replace(PlayerPrincipal who, String orderId, ReplaceOrderRequest req, RequestMeta meta) {
    try {
      gate.preflight(who, true);
      long accountId = accountQueries.activeAccountId(who.playerId());
      gate.chargeWrite(who.playerId());
      PlaceOrderResponse r = engine.call(new EngineCommand.ReplaceOrderCmd(accountId, OrderOrigin.of(who, meta),
          orderId, req, clock.now()));
      if (!r.replayed()) {
        audit(who, meta, "ORDER_REPLACE", AuditOutcome.OK, orderId, null, null);
      }
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_REPLACE", outcome(e), orderId, e, null);
      throw e;
    }
  }

  @Override
  public OrderDto cancel(PlayerPrincipal who, String orderId, RequestMeta meta) {
    try {
      gate.preflight(who, true);
      long accountId = accountQueries.activeAccountId(who.playerId());
      gate.chargeWrite(who.playerId());
      OrderDto r = engine.call(new EngineCommand.CancelOrderCmd(accountId, orderId, CancelReason.USER,
          who.playerId()));
      audit(who, meta, "ORDER_CANCEL", AuditOutcome.OK, orderId, null, null);
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_CANCEL", outcome(e), orderId, e, null);
      throw e;
    }
  }

  @Override
  public List<OrderDto> cancelAll(PlayerPrincipal who, String symbolOrNull, RequestMeta meta) {
    String symbol = NormalizedOrder.normalizeSymbol(symbolOrNull == null || symbolOrNull.isBlank() ? null
        : symbolOrNull);
    try {
      gate.preflight(who, true);
      long accountId = accountQueries.activeAccountId(who.playerId());
      gate.chargeWrite(who.playerId());
      List<OrderDto> r = engine.call(new EngineCommand.CancelAllCmd(accountId, symbol, CancelReason.USER,
          who.playerId()));
      audit(who, meta, "ORDER_CANCEL_ALL", AuditOutcome.OK, symbol, null, null);
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_CANCEL_ALL", outcome(e), symbol, e, null);
      throw e;
    }
  }

  /**
   * §11.8: a MARKET SELL of the full available quantity (position minus other open sells). A retry with the same
   * {@code clientOrderId} replays the order it placed, even after the position is gone.
   */
  @Override
  public PlaceOrderResponse closePosition(PlayerPrincipal who, String symbol, String clientOrderId, String rationale,
                                          RequestMeta meta) {
    String sym = NormalizedOrder.normalizeSymbol(symbol);
    String cid = clientOrderId == null ? null : clientOrderId.trim();
    gate.preflight(who, true);
    OrderReplay.requireClientOrderId(cid);
    AccountRow acc = activeAccount(who.playerId());
    Optional<OrderRow> existing = orders.byClientOrderId(acc.id(), cid);
    if (existing.isPresent()) {
      OrderRow r = existing.get();
      boolean same = r.symbol().equals(sym) && r.side() == Side.SELL && r.type() == OrderType.MARKET;
      return replays.replay(r, same ? r.requestHash() : "");
    }
    BigDecimal held = accounts.position(acc.id(), sym).map(PositionRow::qty).orElse(null);
    if (held == null || held.signum() <= 0) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "You hold no " + sym + ".");
    }
    BigDecimal available = held.subtract(orders.reservedSellQty(acc.id(), sym, -1)).max(Qty.ZERO);
    if (available.signum() <= 0) {
      throw ApiException.of(ErrorCode.INSUFFICIENT_POSITION, "All of your " + sym
              + " is reserved by open sell orders.")
          .withHint("Cancel the open sell orders first.");
    }
    PlaceOrderRequest req = new PlaceOrderRequest(cid, sym, Side.SELL, OrderType.MARKET,
        OrderValidator.qtyOut(available), null, null, null, null, null, TimeInForce.DAY, false, rationale, null,
        Set.of());
    return place(who, req, meta);
  }

  // ------------------------------------------------------------------------------------------- groups (§11.9)

  @Override
  public PlaceOrderGroupResponse placeGroup(PlayerPrincipal who, PlaceOrderGroupRequest req, RequestMeta meta) {
    return groups.place(who, req, meta);
  }

  @Override
  public GroupPreviewDto previewGroup(PlayerPrincipal who, PlaceOrderGroupRequest req) {
    return groups.preview(who, req);
  }

  @Override
  public PlaceOrderGroupResponse replaceGroup(PlayerPrincipal who, String groupId, ReplaceOrderGroupRequest req,
                                              RequestMeta meta) {
    return groups.replace(who, groupId, req, meta);
  }

  @Override
  public OrderGroupDto cancelGroup(PlayerPrincipal who, String groupId, RequestMeta meta) {
    return groups.cancel(who, groupId, meta);
  }

  private String sessionNote(NormalizedOrder o, Instant now) {
    if (o.type() == null) {
      return null;
    }
    try {
      return SessionOrderMatrix.sessionNote(SessionOrderMatrix.accept(clock.calendar(o.symbol()), o.type(),
          o.extendedHours(), o.timeInForce(), clock.session(o.symbol(), now), now), now);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private AccountRow activeAccount(long playerId) {
    return accounts.activeByPlayer(playerId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "You have no active account."));
  }

  private static AuditOutcome outcome(ApiException e) {
    return switch (e.status()) {
      case 429 -> AuditOutcome.RATE_LIMITED;
      case 401, 403 -> AuditOutcome.DENIED;
      case 500, 501, 503 -> AuditOutcome.ERROR;
      default -> AuditOutcome.REJECTED;
    };
  }

  private void audit(PlayerPrincipal who, RequestMeta meta, String action, AuditOutcome outcome, String target,
                     ApiException e, NormalizedOrder o) {
    try {
      AuditEntry entry = AuditEntry.of(AuditCategory.ORDER, action, outcome).withActor(who).withMeta(meta);
      if (target != null) {
        entry = entry.withTarget("order", target);
      }
      if (e != null) {
        entry = entry.withDetail("code", e.code().name());
      }
      if (o != null) {
        entry = entry.withDetail("request", java.util.Map.of(
            "clientOrderId", String.valueOf(o.clientOrderId()), "symbol", String.valueOf(o.symbol()),
            "side", String.valueOf(o.side()), "type", String.valueOf(o.type()),
            "qty", o.qty() == null ? "" : o.qty().toPlainString(),
            "notional", o.notional() == null ? "" : o.notional().toPlainString(),
            "limitPrice", o.limitPrice() == null ? "" : o.limitPrice().toPlainString()));
      }
      audit.record(entry);
    } catch (RuntimeException ignored) {
      // the audit row must never change the order outcome
    }
  }
}
