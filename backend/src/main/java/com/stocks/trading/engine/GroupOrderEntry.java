package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.money.Money;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Qty;
import com.stocks.common.time.Et;
import com.stocks.contract.trading.dto.GroupLegChangeDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.engine.EngineCommand.CancelOrderGroupCmd;
import com.stocks.trading.engine.EngineCommand.PlaceOrderGroupCmd;
import com.stocks.trading.engine.EngineCommand.ReplaceOrderCmd;
import com.stocks.trading.engine.EngineCommand.ReplaceOrderGroupCmd;
import com.stocks.trading.engine.write.OrderGroupWriter;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.order.GroupEvaluation;
import com.stocks.trading.order.GroupEvaluation.LegPlan;
import com.stocks.trading.order.GroupEvaluation.LegSpec;
import com.stocks.trading.order.GroupReplay;
import com.stocks.trading.order.GroupRules;
import com.stocks.trading.order.NormalizedGroup;
import com.stocks.trading.order.NormalizedOrder;
import com.stocks.trading.order.OrderContext;
import com.stocks.trading.order.OrderOrigin;
import com.stocks.trading.order.OrderReplay;
import com.stocks.trading.order.OrderStateMachine;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.order.RequestHasher;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.order.mapper.OrderDtoMapper;
import com.stocks.trading.order.mapper.OrderGroupDtoMapper;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderGroupRow;
import com.stocks.trading.store.OrderGroupStore;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Group order entry on the engine thread (§11.9): place a group (all legs or nothing), replace a group or one of its
 * legs (one cancel-replace of the changed legs, validated as a whole), and cancel a group. The rules that follow
 * fills, cancels and expiries are {@link GroupHandler}'s.
 */
@Component
class GroupOrderEntry {

  private final OrderStore orders;
  private final OrderGroupStore groups;
  private final AccountStore accounts;
  private final OrderWriter writer;
  private final OrderGroupWriter groupWriter;
  private final GroupEvaluation evaluation;
  private final GroupHandler handler;
  private final OrderHandler orderHandler;
  private final LatencyModel latency;
  private final TradingClock clock;
  private final PublicId publicIds;
  private final GroupReplay replays;
  private final OrderReplay orderReplays;
  private final OrderDtoMapper mapper;
  private final OrderGroupDtoMapper groupMapper;
  private final OrderContext context;
  private final JsonMapper json;

  GroupOrderEntry(OrderStore orders, OrderGroupStore groups, AccountStore accounts, OrderWriter writer,
                  OrderGroupWriter groupWriter, GroupEvaluation evaluation, GroupHandler handler,
                  OrderHandler orderHandler, LatencyModel latency, TradingClock clock, PublicId publicIds,
                  GroupReplay replays, OrderReplay orderReplays, OrderDtoMapper mapper,
                  OrderGroupDtoMapper groupMapper, OrderContext context, JsonMapper json) {
    this.orders = orders;
    this.groups = groups;
    this.accounts = accounts;
    this.writer = writer;
    this.groupWriter = groupWriter;
    this.evaluation = evaluation;
    this.handler = handler;
    this.orderHandler = orderHandler;
    this.latency = latency;
    this.clock = clock;
    this.publicIds = publicIds;
    this.replays = replays;
    this.orderReplays = orderReplays;
    this.mapper = mapper;
    this.groupMapper = groupMapper;
    this.context = context;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------- place

  PlaceOrderGroupResponse place(PlaceOrderGroupCmd cmd, EngineTx tx) {
    NormalizedGroup g = cmd.group();
    OrderReplay.requireClientOrderId(g.clientOrderId());
    var replay = replays.lookup(cmd.accountId(), g.clientOrderId(), cmd.requestHash(), GroupReplay.PLACE, null);
    if (replay.isPresent()) {
      return replay.get();
    }
    AccountRow acc = activeAccount(cmd.accountId());
    Instant now = clock.now();
    String grpId = publicIds.next(Prefix.ORDER_GROUP);
    Map<OrderGroupRole, String> ids = new EnumMap<>(OrderGroupRole.class);
    for (OrderGroupRole role : OrderGroupRole.values()) {
      ids.put(role, grpId + ":" + role.name());
    }
    GroupEvaluation.Evaluated ev = evaluation.evaluate(acc, cmd.origin().kind(), g, now, false,
        LegSpec.all(g, ids), g.roles().size(), false);
    orderHandler.ensureAssetRow(ev.legs().get(0).eval().market().asset(), now);
    long groupRowId = groupWriter.insert(grpId, acc.id(), acc.playerId(), g.clientOrderId(), cmd.requestHash(),
        g.type(), g.symbol(), Qty.normalize(g.qty()), g.timeInForce(), now);
    groupWriter.request(acc.id(), groupRowId, g.clientOrderId(), cmd.requestHash(), GroupReplay.PLACE, now);
    Long entryId = null;
    for (LegPlan p : ev.legs()) {
      long id = insertLeg(acc, cmd.origin(), grpId, groupRowId, p, p.role() == OrderGroupRole.ENTRY ? null : entryId,
          null, cmd.receivedAt(), now, tx);
      if (p.role() == OrderGroupRole.ENTRY) {
        entryId = id;
      }
    }
    tx.publish(new AccountChanged(acc.id(), acc.playerId(), "ORDER_ACCEPTED", now));
    OrderGroupRow row = groups.byId(groupRowId).orElseThrow();
    OrderGroupDto dto = groupMapper.toDto(row, orders.legs(groupRowId));
    return new PlaceOrderGroupResponse(dto, ev.estimate(), ev.buyingPowerBefore(), ev.buyingPowerAfter(),
        ev.warnings(), false);
  }

  /** Inserts one PENDING leg (held children wait with {@code AWAITING_PARENT}), its events and, after commit, its index entry. */
  private long insertLeg(AccountRow acc, OrderOrigin origin, String grpPublicId, long groupRowId, LegPlan p,
                         Long parentId, OrderRow replacing, Instant receivedAt, Instant now, EngineTx tx) {
    NormalizedOrder o = p.order();
    String publicId = publicIds.next(Prefix.ORDER);
    Instant eligibleAt = p.held() ? now : p.eval().acceptance().eligibleBase().plus(latency.latency(publicId, 0));
    WaitReason wait = p.held() ? WaitReason.AWAITING_PARENT : p.eval().acceptance().waitReason();
    // public mode (§16.7): a new leg's note is always public; a replace keeps the old flag unless it sends a new note
    boolean rationalePublic = replacing == null || o.shareNote();
    BigDecimal limit = o.limitPrice() == null ? null : Prices.normalize(o.limitPrice());
    BigDecimal stop = o.stopPrice() == null ? null : Prices.normalize(o.stopPrice());
    long id = writer.insert(new OrderWriter.NewOrder(publicId, acc.id(), acc.playerId(), o.clientOrderId(),
        RequestHasher.hash(o), o.symbol(), o.side(), o.type(), Qty.normalize(o.qty()), null, false, limit,
        o.timeInForce(), false, wait, p.reservedCash(), p.reservedQty(), p.eval().expiry().sessionDate(), receivedAt,
        now, eligibleAt, p.eval().expiry().expiresAt(), replacing == null ? null : replacing.id(), origin.channel(),
        origin.apiKeyId(), origin.oauthClientId(), origin.mcpClient(), origin.clientIp(), o.rationale(),
        rationalePublic, stop, null, null, o.doNotReduce(),
        new OrderWriter.GroupLink(groupRowId, p.role(), parentId, p.held())));
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("channel", origin.channel().name());
    if (!p.held()) {
      detail.put("eligibleAt", eligibleAt.toString());
    }
    detail.put("group", grpPublicId);
    detail.put("role", p.role().name());
    detail.put("held", p.held());
    if (replacing != null) {
      detail.put("replaces", replacing.publicId());
    }
    writer.event(id, OrderEventType.ACCEPTED, OrderStatus.PENDING, now, json.writeValueAsString(detail));
    if (!p.held() && p.eval().acceptance().queued()) {
      writer.event(id, OrderEventType.QUEUED, OrderStatus.PENDING, now,
          json.writeValueAsString(Map.of("waitReason", p.eval().acceptance().waitReason().name())));
    }
    tx.afterCommit(() -> orders.byId(id).ifPresent(orderHandler::track));
    tx.publish(new OrderChanged(acc.id(), acc.playerId(), publicId, OrderEventType.ACCEPTED, OrderStatus.PENDING,
        now));
    return id;
  }

  // ------------------------------------------------------------------------------------------- replace

  /** {@code PATCH /order-groups/{groupId}}: the changed legs of a group in one cancel-replace (§11.9). */
  PlaceOrderGroupResponse replaceGroup(ReplaceOrderGroupCmd cmd, EngineTx tx) {
    ReplaceOrderGroupRequest req = cmd.request();
    OrderGroupRow g = groups.byPublicId(cmd.accountId(), cmd.groupId()).orElseThrow(() -> notFound(cmd.groupId()));
    String cid = req.clientOrderId() == null ? null : req.clientOrderId().trim();
    OrderReplay.requireClientOrderId(cid);
    String hash = replaceHash(g.publicId(), req);
    var replay = replays.lookup(cmd.accountId(), cid, hash, GroupReplay.REPLACE, g.id());
    if (replay.isPresent()) {
      return replay.get();
    }
    List<OrderRow> legs = handler.current(g.id());
    NormalizedGroup cur = currentGroup(g, legs);
    NormalizedGroup merged = cur.withQtyAndTif(req.qty() != null ? req.qty() : cur.qty(),
        req.timeInForce() != null ? req.timeInForce() : cur.timeInForce());
    Set<OrderGroupRole> priced = java.util.EnumSet.noneOf(OrderGroupRole.class);
    for (OrderGroupRole role : cur.roles()) {
      GroupLegChangeDto ch = switch (role) {
        case ENTRY -> req.entry();
        case TAKE_PROFIT -> req.takeProfit();
        case STOP_LOSS -> req.stopLoss();
        case SECONDARY -> req.secondary();
      };
      if (ch != null && (ch.limitPrice() != null || ch.stopPrice() != null)) {
        NormalizedGroup.Leg l = cur.leg(role);
        merged = merged.withLeg(role, new NormalizedGroup.Leg(l.type(),
            ch.limitPrice() != null ? ch.limitPrice() : l.limitPrice(),
            ch.stopPrice() != null ? ch.stopPrice() : l.stopPrice()));
        priced.add(role);
      }
    }
    for (String extra : List.of("entry", "takeProfit", "stopLoss", "secondary")) {
      boolean given = switch (extra) {
        case "entry" -> req.entry() != null;
        case "takeProfit" -> req.takeProfit() != null;
        case "stopLoss" -> req.stopLoss() != null;
        default -> req.secondary() != null;
      };
      if (given && cur.roles().stream().noneMatch(r -> GroupRules.field(r).equals(extra))) {
        throw ApiException.of(ErrorCode.ORDER_GROUP_INVALID, "A " + g.type() + " has no " + extra + " leg.")
            .withField(extra, ErrorCode.ORDER_GROUP_INVALID, "No such leg");
      }
    }
    if (req.rationale() != null && !req.rationale().isBlank()) {
      merged = merged.withRationale(req.rationale().trim());
    }
    AccountRow acc = activeAccount(cmd.accountId());
    Map<OrderGroupRole, String> ids = new EnumMap<>(OrderGroupRole.class);
    for (OrderGroupRole role : cur.roles()) {
      ids.put(role, nextLegClientId(g, role));
    }
    Replaced r = replaceLegs(acc, cmd.origin(), g, legs, merged, priced, ids, cmd.receivedAt(), tx);
    groupWriter.replaced(g.id(), cid, hash, Qty.normalize(merged.qty()), merged.timeInForce(), clock.now());
    groupWriter.request(acc.id(), g.id(), cid, hash, GroupReplay.REPLACE, clock.now());
    OrderGroupRow fresh = groups.byId(g.id()).orElseThrow();
    OrderGroupDto dto = groupMapper.toDto(fresh, orders.legs(g.id()));
    return new PlaceOrderGroupResponse(dto, r.ev().estimate(), r.ev().buyingPowerBefore(),
        context.balances(acc, Et.date(clock.now())).buyingPower(), r.ev().warnings(), false);
  }

  /**
   * {@code PATCH /orders/{orderId}} on a group leg (§11.9): its prices, and its quantity only through the entry (or
   * either leg of an OCO), the children following. Time in force and extended hours belong to the group.
   */
  PlaceOrderResponse replaceLeg(ReplaceOrderCmd cmd, EngineTx tx) {
    ReplaceOrderRequest req = cmd.request();
    OrderRow old = orders.byPublicId(cmd.accountId(), cmd.orderId())
        .orElseThrow(() -> OrderHandler.notFound(cmd.orderId()));
    String newClientId = req.clientOrderId() == null ? null : req.clientOrderId().trim();
    OrderReplay.requireClientOrderId(newClientId);
    OrderGroupRow g = groups.byId(old.groupId()).orElseThrow();
    List<OrderRow> legs = handler.current(g.id());
    NormalizedGroup cur = currentGroup(g, legs);
    OrderGroupRole role = old.groupRole();
    if (req.trailAmount() != null || req.trailPercent() != null) {
      throw invalid("trailAmount", "Group legs have no trail.");
    }
    if (Boolean.TRUE.equals(req.extendedHours())) {
      throw invalid("extendedHours", "Group legs work in the regular session only.");
    }
    if (req.timeInForce() != null && req.timeInForce() != old.timeInForce()) {
      throw invalid("timeInForce", "A group's legs share its time in force; change it with "
          + "PATCH /api/v1/order-groups/" + g.publicId() + ".");
    }
    boolean qtyChange = req.qty() != null && req.qty().compareTo(old.qty()) != 0;
    if (qtyChange && role != OrderGroupRole.ENTRY && g.type() != OrderGroupType.OCO) {
      throw invalid("qty", "A child's quantity follows its entry; change the entry's quantity instead.");
    }
    NormalizedGroup merged = qtyChange ? cur.withQtyAndTif(req.qty(), cur.timeInForce()) : cur;
    NormalizedGroup.Leg l = cur.leg(role);
    Set<OrderGroupRole> priced = java.util.EnumSet.noneOf(OrderGroupRole.class);
    if (req.limitPrice() != null || req.stopPrice() != null) {
      merged = merged.withLeg(role, new NormalizedGroup.Leg(l.type(),
          req.limitPrice() != null ? req.limitPrice() : l.limitPrice(),
          req.stopPrice() != null ? req.stopPrice() : l.stopPrice()));
    }
    priced.add(role);     // the leg itself is always replaced (a new clientOrderId)
    if (req.rationale() != null && !req.rationale().isBlank()) {
      merged = merged.withRationale(req.rationale().trim());
    }
    NormalizedOrder replacement = merged.order(role, newClientId);
    if (!qtyChange && old.qty() != null) {
      replacement = replacement.withQty(old.qty());
    }
    String hash = RequestHasher.hash(replacement);
    var existing = orders.byClientOrderId(cmd.accountId(), newClientId);
    if (existing.isPresent()) {
      return orderReplays.replay(existing.get(), hash);
    }
    if (!old.isOpen()) {
      throw ApiException.of(ErrorCode.ORDER_NOT_REPLACEABLE, "Order " + old.publicId() + " is " + old.status()
              + " and cannot be replaced.")
          .withHint("Only open, unfilled legs can be replaced.");
    }
    AccountRow acc = activeAccount(cmd.accountId());
    Map<OrderGroupRole, String> ids = new EnumMap<>(OrderGroupRole.class);
    for (OrderGroupRole r : cur.roles()) {
      ids.put(r, r == role ? newClientId : nextLegClientId(g, r));
    }
    Replaced r = replaceLegs(acc, cmd.origin(), g, legs, merged, priced, ids, cmd.receivedAt(), tx);
    if (qtyChange) {
      groupWriter.replaced(g.id(), g.clientOrderId(), g.requestHash(), Qty.normalize(merged.qty()),
          g.timeInForce(), clock.now());
    }
    OrderRow fresh = orders.byId(r.newIds().get(role)).orElseThrow();
    LegPlan plan = r.ev().leg(role);
    return new PlaceOrderResponse(mapper.toDto(fresh), plan.eval().result().estimate(),
        r.ev().buyingPowerBefore(), context.balances(acc, Et.date(clock.now())).buyingPower(),
        r.ev().warnings(), false);
  }

  /** The result of a replace: the evaluation and the new leg ids by role. */
  private record Replaced(GroupEvaluation.Evaluated ev, Map<OrderGroupRole, Long> newIds) {}

  /**
   * The cancel-replace of §11.9 in one step. {@code priced}: legs whose prices (or client id) change; a quantity or
   * time-in-force change also replaces every other open live leg, and held children follow in place. Every replaced
   * leg must be open and unfilled; the old legs are REPLACED first (their holds and the pair's reservation credited
   * back), then the merged group is validated and the new legs inserted; any failure rolls everything back.
   */
  private Replaced replaceLegs(AccountRow acc, OrderOrigin origin, OrderGroupRow g, List<OrderRow> legs,
                               NormalizedGroup merged, Set<OrderGroupRole> priced, Map<OrderGroupRole, String> ids,
                               Instant receivedAt, EngineTx tx) {
    Instant now = clock.now();
    NormalizedGroup cur = currentGroup(g, legs);
    boolean qtyChange = merged.qty().compareTo(cur.qty()) != 0;
    boolean tifChange = merged.timeInForce() != cur.timeInForce();
    Map<OrderGroupRole, OrderRow> byRole = new EnumMap<>(OrderGroupRole.class);
    for (OrderRow r : legs) {
      byRole.put(r.groupRole(), r);
    }
    // the legs to re-insert
    Set<OrderGroupRole> replaced = java.util.EnumSet.noneOf(OrderGroupRole.class);
    replaced.addAll(priced);
    if (qtyChange || tifChange) {
      for (OrderRow r : legs) {
        if (r.isOpen() && !r.held() && (qtyChange || merged.tifOf(r.groupRole()) != r.timeInForce())) {
          replaced.add(r.groupRole());
        }
      }
    }
    if (replaced.isEmpty() && !qtyChange && !tifChange) {
      throw ApiException.validation("clientOrderId", "Nothing to change: send a new qty, timeInForce or a leg price.");
    }
    if (qtyChange && legs.stream().anyMatch(r -> r.filledQty().signum() > 0)) {
      throw notReplaceable(g, "a group with fills cannot change its quantity");
    }
    for (OrderGroupRole role : replaced) {
      OrderRow r = byRole.get(role);
      boolean unfilledOpen = r != null && r.isOpen() && r.filledQty().signum() == 0 && !r.cancelPending()
          && (r.held() || r.status() == OrderStatus.PENDING || r.status() == OrderStatus.WORKING);
      if (!unfilledOpen) {
        throw notReplaceable(g, GroupRules.label(role).toLowerCase(java.util.Locale.ROOT) + " "
            + (r == null ? "is missing" : "is " + r.apiStatus() + (r.filledQty().signum() > 0 ? " with fills" : "")));
      }
    }
    // credit back: the pair reservation of the unchanged legs, and the replaced legs' holds
    for (OrderRow r : legs) {
      if (r.isOpen() && GroupHandler.inPair(r) && !replaced.contains(r.groupRole())) {
        writer.setReservations(r.id(), Money.ZERO, Qty.ZERO);
      }
    }
    for (OrderGroupRole role : replaced) {
      OrderRow r = byRole.get(role);
      writer.finish(r.id(), OrderStateMachine.transition(r.status(), OrderStatus.REPLACED),
          CancelReason.REPLACED_BY_USER.name(), now);
    }
    // validate the merged group (only the replaced legs run the order checks)
    OrderRow entryRow = byRole.get(OrderGroupRole.ENTRY);
    boolean entryExecuted = entryRow != null && entryRow.filledQty().signum() > 0;
    List<LegSpec> specs = new ArrayList<>();
    for (OrderGroupRole role : merged.roles()) {
      if (!replaced.contains(role)) {
        continue;
      }
      OrderRow r = byRole.get(role);
      BigDecimal qty = qtyChange ? null : r.qty();
      specs.add(new LegSpec(role, ids.get(role), qty, merged.held(role) && !r.held()));
    }
    GroupEvaluation.Evaluated ev = evaluation.evaluate(acc, origin.kind(), merged, now, false, specs, 0,
        entryExecuted);
    // insert the new legs
    Map<OrderGroupRole, Long> newIds = new EnumMap<>(OrderGroupRole.class);
    Long entryId = entryRow == null ? null : entryRow.id();
    for (LegPlan p : ev.legs()) {
      OrderRow old = byRole.get(p.role());
      Long parent = p.role() == OrderGroupRole.ENTRY || g.type() == OrderGroupType.OCO ? null
          : newIds.getOrDefault(OrderGroupRole.ENTRY, entryId);
      long id = insertLeg(acc, origin, g.publicId(), g.id(), p, parent, old, receivedAt, now, tx);
      newIds.put(p.role(), id);
      writer.linkReplacement(old.id(), id);
      String newPublicId = orders.byId(id).orElseThrow().publicId();
      writer.event(old.id(), OrderEventType.REPLACED, OrderStatus.REPLACED, now,
          json.writeValueAsString(Map.of("replacedBy", newPublicId)));
      tx.afterCommit(() -> orderHandler.untrack(old.id()));
      tx.publish(new OrderChanged(old.accountId(), old.playerId(), old.publicId(), OrderEventType.REPLACED,
          OrderStatus.REPLACED, now));
    }
    // the children of a replaced entry follow it; held children follow the quantity and time in force in place
    Long newEntry = newIds.get(OrderGroupRole.ENTRY);
    for (OrderRow r : legs) {
      if (replaced.contains(r.groupRole()) || !r.isOpen()) {
        continue;
      }
      if (newEntry != null && r.parentOrderId() != null) {
        writer.relinkParent(r.id(), newEntry);
      }
      if (r.held() && qtyChange) {
        writer.setQty(r.id(), Qty.normalize(merged.qty()));
        writer.event(r.id(), OrderEventType.QTY_ADJUSTED, r.status(), now,
            json.writeValueAsString(Map.of("qty", OrderValidator.qtyOut(merged.qty()))));
        tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.QTY_ADJUSTED,
            r.status(), now));
      }
      if (r.held() && tifChange) {
        writer.setTimeInForce(r.id(), merged.tifOf(r.groupRole()));
      }
    }
    sharePairCash(acc, g);
    handler.rebalance(g.id());
    if (context.balances(acc, Et.date(now)).buyingPower().signum() < 0) {
      throw ApiException.of(ErrorCode.INSUFFICIENT_BUYING_POWER, "The replaced group needs more buying power than "
          + "the account has.");
    }
    groupWriter.touch(g.id(), now);
    tx.publish(new AccountChanged(acc.id(), acc.playerId(), "ORDER_ACCEPTED", now));
    return new Replaced(ev, newIds);
  }

  /** A BUY OCO pair holds the larger of its two holds once, on the take profit if open (§11.9). */
  private void sharePairCash(AccountRow acc, OrderGroupRow g) {
    List<OrderRow> pair = orders.legs(g.id()).stream()
        .filter(r -> r.isOpen() && GroupHandler.inPair(r) && r.side() == Side.BUY)
        .sorted(Comparator.comparing(OrderRow::groupRole)).toList();
    if (pair.isEmpty()) {
      return;
    }
    BigDecimal needed = Money.ZERO;
    for (OrderRow r : pair) {
      needed = needed.max(r.reservedCash().max(handler.ownBuyHold(r)));
    }
    for (int i = 0; i < pair.size(); i++) {
      writer.setReservations(pair.get(i).id(), i == 0 ? needed : Money.ZERO, Qty.ZERO);
    }
  }

  // ------------------------------------------------------------------------------------------- cancel

  /**
   * {@code DELETE /order-groups/{groupId}}: every open leg, children first (§11.9). With cancel latency (§11.10) a
   * working entry's cancel is only requested and its held children stay held until it ends.
   */
  OrderGroupDto cancelGroup(CancelOrderGroupCmd cmd, EngineTx tx) {
    OrderGroupRow g = groups.byPublicId(cmd.accountId(), cmd.groupId()).orElseThrow(() -> notFound(cmd.groupId()));
    Instant now = clock.now();
    List<OrderRow> open = handler.current(g.id()).stream().filter(OrderRow::isOpen)
        .sorted(Comparator.comparing((OrderRow r) -> r.parentOrderId() == null).thenComparingLong(OrderRow::id))
        .toList();
    orderHandler.cancelOrRequestAll(open, CancelReason.USER, cmd.actorPlayerId(), now, tx);
    return groupMapper.toDto(groups.byId(g.id()).orElseThrow(), orders.legs(g.id()));
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** The group as a request, from its current legs (the base a replace changes). */
  static NormalizedGroup currentGroup(OrderGroupRow g, List<OrderRow> current) {
    Map<OrderGroupRole, NormalizedGroup.Leg> legs = new EnumMap<>(OrderGroupRole.class);
    Side side = Side.BUY;
    String rationale = null;
    boolean dnr = false;
    boolean shared = false;
    for (OrderRow r : current) {
      legs.put(r.groupRole(), new NormalizedGroup.Leg(r.type(), r.limitPrice(), r.stopPrice()));
      if (g.type() == OrderGroupType.OCO || r.groupRole() == OrderGroupRole.ENTRY) {
        side = r.side();
      }
      rationale = r.rationale() != null ? r.rationale() : rationale;
      dnr |= r.doNotReduce();
      shared |= r.rationalePublic();
    }
    return new NormalizedGroup(g.clientOrderId(), g.type(), g.symbol(), side, g.qty(), g.timeInForce(),
        legs.get(OrderGroupRole.ENTRY), legs.get(OrderGroupRole.TAKE_PROFIT), legs.get(OrderGroupRole.STOP_LOSS),
        legs.get(OrderGroupRole.SECONDARY), rationale, shared, dnr, Set.of());
  }

  /** {@code <grp>:<ROLE>:<n>}: the client id of a leg a group replace creates. */
  private String nextLegClientId(OrderGroupRow g, OrderGroupRole role) {
    long n = orders.legs(g.id()).stream().filter(r -> r.groupRole() == role).count() + 1;
    return g.publicId() + ":" + role.name() + ":" + n;
  }

  /** A group replace's idempotency hash: the group and the normalized change (rationale excluded). */
  static String replaceHash(String groupPublicId, ReplaceOrderGroupRequest req) {
    Map<String, Object> f = new TreeMap<>();
    f.put("group", groupPublicId);
    f.put("qty", dec(req.qty()));
    f.put("timeInForce", req.timeInForce() == null ? null : req.timeInForce().name());
    put(f, "entry", req.entry());
    put(f, "takeProfit", req.takeProfit());
    put(f, "stopLoss", req.stopLoss());
    put(f, "secondary", req.secondary());
    return RequestHasher.sha256Hex(f.toString());
  }

  private static void put(Map<String, Object> f, String leg, GroupLegChangeDto c) {
    if (c != null) {
      f.put(leg + ".limitPrice", dec(c.limitPrice()));
      f.put(leg + ".stopPrice", dec(c.stopPrice()));
    }
  }

  private static String dec(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal s = v.stripTrailingZeros();
    return (s.scale() < 0 ? s.setScale(0, RoundingMode.UNNECESSARY) : s).toPlainString();
  }

  private AccountRow activeAccount(long accountId) {
    AccountRow acc = accounts.byId(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "No such account."));
    if (!acc.isActive()) {
      throw ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "The account is closed (reset); use the active run.");
    }
    return acc;
  }

  static ApiException notFound(String groupId) {
    return ApiException.of(ErrorCode.NOT_FOUND, "No order group " + groupId + ".");
  }

  private static ApiException notReplaceable(OrderGroupRow g, String why) {
    return ApiException.of(ErrorCode.ORDER_NOT_REPLACEABLE, "Group " + g.publicId() + " cannot be replaced: " + why
            + ".")
        .withHint("Only open, unfilled legs can be replaced; cancel the group and place a new one instead.");
  }

  private static ApiException invalid(String field, String message) {
    return ApiException.of(ErrorCode.ORDER_GROUP_INVALID, message)
        .withField(field, ErrorCode.ORDER_GROUP_INVALID, message);
  }

}
