package com.stocks.trading.order;

import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.Side;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.ProblemField;
import com.stocks.common.error.Warning;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.trading.dto.EstimateDto;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.store.AccountRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates a group order (§11.9) against an account and the market, for preview (request thread, committed state)
 * and for {@code PlaceOrderGroupCmd} / {@code ReplaceOrderGroupCmd} (engine thread, where only that result counts):
 * the group shape, the bracket price rules, then every leg through the ordinary checks: live legs (an entry, both legs
 * of an OCO) checks 7–27, held children checks 7–20 against the entry price. The first failure wins; a leg's error
 * names the leg ({@code takeProfit.limitPrice}). Success yields each leg's plan and the group's reservations: one
 * reservation per OCO pair (a SELL pair reserves its shares once, a BUY pair the larger of its two holds).
 */
@Component
public class GroupEvaluation {

  /** Group-level request members: an error on one of them is not prefixed with the leg. */
  private static final Set<String> GROUP_FIELDS = Set.of("qty", "symbol", "side", "timeInForce", "rationale",
      "acknowledge", "clientOrderId", "notional", "extendedHours", "orderId");

  /**
   * One leg as it will be inserted: its role, the order, whether it is held, its evaluation and the reservation it
   * carries (after the OCO pair sharing).
   */
  public record LegPlan(OrderGroupRole role, NormalizedOrder order, boolean held, OrderEvaluation.Evaluated eval,
                        BigDecimal reservedCash, BigDecimal reservedQty) {}

  /** A valid group. {@code estimate}: the entry's, or the OCO's take-profit leg's. */
  public record Evaluated(NormalizedGroup group, BigDecimal entryPrice, List<LegPlan> legs,
                          BigDecimal buyingPowerBefore, BigDecimal buyingPowerAfter, List<Warning> warnings,
                          EstimateDto estimate, String sessionNote) {

    public LegPlan leg(OrderGroupRole role) {
      return legs.stream().filter(l -> l.role() == role).findFirst().orElse(null);
    }
  }

  private final OrderEvaluation evaluation;
  private final OrderContext context;
  private final RulesProperties rules;
  private final TradingClock clock;

  public GroupEvaluation(OrderEvaluation evaluation, OrderContext context, RulesProperties rules,
                         TradingClock clock) {
    this.evaluation = evaluation;
    this.context = context;
    this.rules = rules;
    this.clock = clock;
  }

  /**
   * One leg to evaluate: its role and {@code clientOrderId}; {@code qtyOrNull} overrides the group quantity (a
   * replaced child keeps its own, trimmed quantity); {@code released}: a child whose entry already executed (a
   * replace), which works like a live order but takes its shares from the group's reservation.
   */
  public record LegSpec(OrderGroupRole role, String clientOrderId, BigDecimal qtyOrNull, boolean released) {

    public static List<LegSpec> all(NormalizedGroup g, Map<OrderGroupRole, String> clientIds) {
      return g.roles().stream().map(r -> new LegSpec(r, clientIds.get(r), null, false)).toList();
    }
  }

  /**
   * Evaluates the legs of {@code specs} (every leg of a new group, or the changed legs of a replace) of {@code g}.
   * {@code newOrders}: how many open orders the call adds (check 22 counts them all; 0 for a replace).
   * {@code entryExecuted}: the entry already has fills (a replace), so the bracket price rules no longer apply (a
   * stop may be moved above the entry to lock in a gain) and the children are checked against the market.
   *
   * @throws ApiException the first failing rule
   */
  public Evaluated evaluate(AccountRow acc, PlayerKind kind, NormalizedGroup g, Instant now, boolean preview,
                            List<LegSpec> specs, int newOrders, boolean entryExecuted) {
    GroupRules.requireShape(g, rules.maxOrderQty());
    BigDecimal entryPrice = g.type().hasEntry() && !entryExecuted ? entryPrice(g, now) : null;
    GroupRules.requireBracketPrices(g, entryPrice);

    List<LegPlan> plans = new ArrayList<>();
    for (LegSpec spec : specs) {
      OrderGroupRole role = spec.role();
      NormalizedOrder o = g.order(role, spec.clientOrderId());
      if (spec.qtyOrNull() != null) {
        o = o.withQty(spec.qtyOrNull());
      }
      boolean held = g.held(role) && !spec.released();
      OrderEvaluation.Evaluated ev;
      try {
        if (held) {
          ev = evaluation.evaluateHeldChild(acc, kind, o, now, preview, entryPrice);
        } else if (spec.released()) {
          ev = released(acc, kind, o, now, preview);
        } else {
          ev = evaluation.evaluate(acc, kind, o, now, preview, Math.max(0, newOrders - 1));
        }
      } catch (ApiException e) {
        throw forLeg(e, role, held, entryPrice);
      }
      plans.add(new LegPlan(role, o, held, ev, ev.result().reservedCash(), ev.result().reservedQty()));
    }
    plans = sharePair(g, plans);

    BigDecimal bpBefore = context.balances(acc, Et.date(now)).buyingPower();
    BigDecimal held = plans.stream().filter(p -> g.sideOf(p.role()) == Side.BUY).map(LegPlan::reservedCash)
        .reduce(Money.ZERO, BigDecimal::add);
    List<Warning> warnings = warnings(plans);
    LegPlan main = plans.stream().filter(p -> p.role() == OrderGroupRole.ENTRY).findFirst()
        .orElse(plans.isEmpty() ? null : plans.get(0));
    EstimateDto estimate = main == null ? null : main.eval().result().estimate();
    String note = plans.stream().filter(p -> !p.held()).findFirst()
        .map(p -> SessionOrderMatrix.sessionNote(p.eval().acceptance(), now)).orElse(null);
    return new Evaluated(g, entryPrice, List.copyOf(plans), bpBefore, bpBefore.subtract(held), warnings, estimate,
        note);
  }

  /**
   * A released child being replaced: checks 7–20 against the market (it works now: a stop on the wrong side would
   * trigger at once), without the position checks (its shares are the group's, already reserved), with its own
   * session acceptance and expiry from now.
   */
  private OrderEvaluation.Evaluated released(AccountRow acc, PlayerKind kind, NormalizedOrder o, Instant now,
                                             boolean preview) {
    BigDecimal ref = context.market(o.symbol(), Et.date(now)).reference();
    OrderEvaluation.Evaluated ev = evaluation.evaluateHeldChild(acc, kind, o, now, preview, ref);
    SessionOrderMatrix.Acceptance a = SessionOrderMatrix.accept(clock.calendar(), o.type(), false, o.timeInForce(),
        clock.session(now), now);
    ExpiryCalculator.Expiry expiry = ExpiryCalculator.compute(clock.calendar(), rules, now, false, o.timeInForce());
    return new OrderEvaluation.Evaluated(a, expiry, ev.result(), ev.market());
  }

  /**
   * The entry price of §11.9: the entry's limit (LIMIT, STOP_LIMIT) or stop (STOP); for a MARKET entry the ask,
   * else the reference price of check 19.
   *
   * @throws ApiException {@code MARKET_DATA_UNAVAILABLE} for a MARKET entry without any price
   */
  BigDecimal entryPrice(NormalizedGroup g, Instant now) {
    NormalizedGroup.Leg e = g.entry();
    if (e == null || e.type() == null) {
      return null;
    }
    return switch (e.type()) {
      case LIMIT, STOP_LIMIT -> positive(e.limitPrice());
      case STOP -> positive(e.stopPrice());
      default -> {
        OrderValidator.MarketSnapshot m = context.market(g.symbol(), Et.date(now));
        QuoteView q = m.quote();
        BigDecimal ask = q == null ? null : positive(q.ask());
        BigDecimal px = ask != null ? ask : positive(m.reference());
        if (px == null && m.asset() != null) {
          throw ApiException.of(ErrorCode.MARKET_DATA_UNAVAILABLE, "No price is available for " + g.symbol()
                  + " to check the take profit and stop loss against a market entry.")
              .withHint("Use a LIMIT entry, or retry when a quote is available.");
        }
        yield px;
      }
    };
  }

  /**
   * One reservation per OCO pair (§11.9): a SELL pair reserves its shares once, on the take profit; a BUY pair (OCO
   * only) the larger of its two holds, on the take profit. Held pairs reserve nothing yet.
   */
  static List<LegPlan> sharePair(NormalizedGroup g, List<LegPlan> plans) {
    LegPlan tp = find(plans, OrderGroupRole.TAKE_PROFIT);
    LegPlan sl = find(plans, OrderGroupRole.STOP_LOSS);
    if (tp == null || sl == null || tp.held() || sl.held()) {
      return plans;
    }
    List<LegPlan> out = new ArrayList<>();
    for (LegPlan p : plans) {
      if (p == tp) {
        out.add(new LegPlan(p.role(), p.order(), p.held(), p.eval(), tp.reservedCash().max(sl.reservedCash()),
            tp.reservedQty().max(sl.reservedQty())));
      } else if (p == sl) {
        out.add(new LegPlan(p.role(), p.order(), p.held(), p.eval(), Money.ZERO, Qty.ZERO));
      } else {
        out.add(p);
      }
    }
    return out;
  }

  private static LegPlan find(List<LegPlan> plans, OrderGroupRole role) {
    return plans.stream().filter(p -> p.role() == role).findFirst().orElse(null);
  }

  /** Every leg's warnings, each prefixed with the leg ("Stop loss: …"), without duplicates. */
  static List<Warning> warnings(List<LegPlan> plans) {
    Set<Warning> out = new LinkedHashSet<>();
    for (LegPlan p : plans) {
      for (Warning w : p.eval().result().warnings()) {
        out.add(new Warning(w.code(), GroupRules.label(p.role()) + ": " + w.message()));
      }
    }
    return List.copyOf(out);
  }

  /**
   * A leg's error, named after the leg: its leg fields are prefixed ({@code stopLoss.stopPrice}), its message starts
   * with the leg's label; a held child's wrong-side stop is reported against the entry price.
   */
  static ApiException forLeg(ApiException e, OrderGroupRole role, boolean held, BigDecimal entryPrice) {
    String label = GroupRules.label(role);
    String detail = e.detail();
    if (held && e.code() == ErrorCode.STOP_PRICE_WRONG_SIDE && entryPrice != null) {
      detail = "the stop must be below the entry price (" + entryPrice.stripTrailingZeros().toPlainString() + ").";
    }
    ApiException out = ApiException.of(e.code(), label + ": " + lower(detail)).withHint(e.hint());
    List<ProblemField> fields = new ArrayList<>();
    for (ProblemField f : e.fields()) {
      String name = f.field() == null || GROUP_FIELDS.contains(f.field()) ? f.field()
          : GroupRules.field(role) + "." + f.field();
      fields.add(new ProblemField(name, f.code(), f.message()));
    }
    if (fields.isEmpty() && e.code() != ErrorCode.VALIDATION_FAILED) {
      fields.add(new ProblemField(GroupRules.field(role), e.code().name(), detail));
    }
    out = out.withFields(fields);
    return e.retryAfterS() == null ? out : out.withRetryAfter(e.retryAfterS());
  }

  private static String lower(String s) {
    if (s == null || s.isEmpty() || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) {
      return s;
    }
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private static BigDecimal positive(BigDecimal v) {
    return v == null || v.signum() <= 0 ? null : v;
  }
}
