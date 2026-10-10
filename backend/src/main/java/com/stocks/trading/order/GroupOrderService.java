package com.stocks.trading.order;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Money;
import com.stocks.common.time.Et;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Group order entry on the request thread (§11.9), as {@code OrderServiceImpl} does it for single orders:
 * {@link OrderGate} (checks 1–6, one write token per group request, replays free), then one engine command whose
 * checks are authoritative. Preview evaluates against committed state and always answers. Every outcome is audited
 * ({@code ORDER/ORDER_GROUP_*}).
 */
@Component
public class GroupOrderService {

  private final OrderGate gate;
  private final EngineCalls engine;
  private final AccountQueries accountQueries;
  private final AccountStore accounts;
  private final GroupReplay replays;
  private final GroupEvaluation evaluation;
  private final OrderContext context;
  private final TradingClock clock;
  private final AuditService audit;

  public GroupOrderService(OrderGate gate, EngineCalls engine, AccountQueries accountQueries, AccountStore accounts,
                           GroupReplay replays, GroupEvaluation evaluation, OrderContext context, TradingClock clock,
                           AuditService audit) {
    this.gate = gate;
    this.engine = engine;
    this.accountQueries = accountQueries;
    this.accounts = accounts;
    this.replays = replays;
    this.evaluation = evaluation;
    this.context = context;
    this.clock = clock;
    this.audit = audit;
  }

  public PlaceOrderGroupResponse place(PlayerPrincipal who, PlaceOrderGroupRequest req, RequestMeta meta) {
    NormalizedGroup g = NormalizedGroup.of(req);
    Instant received = clock.now();
    try {
      gate.preflight(who, true);
      OrderReplay.requireClientOrderId(g.clientOrderId());
      long accountId = accountQueries.activeAccountId(who.playerId());
      String hash = g.hash();
      Optional<PlaceOrderGroupResponse> replay = replays.lookup(accountId, g.clientOrderId(), hash, GroupReplay.PLACE,
          null);
      if (replay.isPresent()) {
        return replay.get();                                     // fast path: free (§11.2 check 6)
      }
      gate.chargeWrite(who.playerId());
      PlaceOrderGroupResponse r = engine.call(new EngineCommand.PlaceOrderGroupCmd(accountId,
          OrderOrigin.of(who, meta), g, hash, received));
      if (r.replayed()) {
        gate.refundWrite(who.playerId());
      } else {
        audit(who, meta, "ORDER_GROUP_PLACE", AuditOutcome.OK, r.group().groupId(), null, g);
      }
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_GROUP_REJECT", outcome(e), null, e, g);
      throw e;
    }
  }

  public GroupPreviewDto preview(PlayerPrincipal who, PlaceOrderGroupRequest req) {
    NormalizedGroup g = NormalizedGroup.of(req);
    BigDecimal bpBefore = Money.ZERO;
    try {
      gate.preflight(who, false);
      if (g.clientOrderId() != null) {
        OrderReplay.requireClientOrderId(g.clientOrderId());
      }
      AccountRow acc = accounts.activeByPlayer(who.playerId())
          .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "You have no active account."));
      Instant now = clock.now();
      bpBefore = context.balances(acc, Et.date(now)).buyingPower();
      Map<OrderGroupRole, String> ids = new EnumMap<>(OrderGroupRole.class);
      for (OrderGroupRole role : OrderGroupRole.values()) {
        ids.put(role, "preview:" + role.name());
      }
      GroupEvaluation.Evaluated ev = evaluation.evaluate(acc, who.kind(), g, now, true,
          GroupEvaluation.LegSpec.all(g, ids), g.roles().size(), false);
      List<GroupPreviewDto.GroupLegPreviewDto> legs = new ArrayList<>();
      for (GroupEvaluation.LegPlan p : ev.legs()) {
        legs.add(new GroupPreviewDto.GroupLegPreviewDto(p.role(), p.order().type(), p.order().side(),
            p.eval().result().estimate()));
      }
      return new GroupPreviewDto(true, List.of(), ev.warnings(), legs, ev.buyingPowerBefore(),
          ev.buyingPowerAfter(), ev.sessionNote());
    } catch (ApiException e) {
      String field = e.fields().isEmpty() ? null : e.fields().get(0).field();
      return new GroupPreviewDto(false, List.of(new PreviewDto.PreviewErrorDto(e.code().name(), field, e.detail(),
          e.hint())), List.of(), List.of(), bpBefore, null, null);
    }
  }

  public PlaceOrderGroupResponse replace(PlayerPrincipal who, String groupId, ReplaceOrderGroupRequest req,
                                         RequestMeta meta) {
    try {
      gate.preflight(who, true);
      long accountId = accountQueries.activeAccountId(who.playerId());
      gate.chargeWrite(who.playerId());
      PlaceOrderGroupResponse r = engine.call(new EngineCommand.ReplaceOrderGroupCmd(accountId,
          OrderOrigin.of(who, meta), groupId, req, clock.now()));
      if (r.replayed()) {
        gate.refundWrite(who.playerId());
      } else {
        audit(who, meta, "ORDER_GROUP_REPLACE", AuditOutcome.OK, groupId, null, null);
      }
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_GROUP_REPLACE", outcome(e), groupId, e, null);
      throw e;
    }
  }

  public OrderGroupDto cancel(PlayerPrincipal who, String groupId, RequestMeta meta) {
    try {
      gate.preflight(who, true);
      long accountId = accountQueries.activeAccountId(who.playerId());
      gate.chargeWrite(who.playerId());
      OrderGroupDto r = engine.call(new EngineCommand.CancelOrderGroupCmd(accountId, groupId, who.playerId()));
      audit(who, meta, "ORDER_GROUP_CANCEL", AuditOutcome.OK, groupId, null, null);
      return r;
    } catch (ApiException e) {
      audit(who, meta, "ORDER_GROUP_CANCEL", outcome(e), groupId, e, null);
      throw e;
    }
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
                     ApiException e, NormalizedGroup g) {
    try {
      AuditEntry entry = AuditEntry.of(AuditCategory.ORDER, action, outcome).withActor(who).withMeta(meta);
      if (target != null) {
        entry = entry.withTarget("order_group", target);
      }
      if (e != null) {
        entry = entry.withDetail("code", e.code().name());
      }
      if (g != null) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(g.describe());
        request.put("clientOrderId", String.valueOf(g.clientOrderId()));
        entry = entry.withDetail("request", request);
      }
      audit.record(entry);
    } catch (RuntimeException ignored) {
      // the audit row must never change the order outcome
    }
  }
}
