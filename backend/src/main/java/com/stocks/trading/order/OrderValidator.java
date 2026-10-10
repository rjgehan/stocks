package com.stocks.trading.order;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.Warning;
import com.stocks.common.error.WarningCode;
import com.stocks.common.money.Money;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.dto.EstimateDto;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.fees.FeeCalculator;
import com.stocks.trading.fees.Fees;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Order checks 7–27 of §11.2, in table order; the first failure wins and is thrown as an {@link ApiException} with
 * its stable code. A pure function of its input: preview runs it against committed state on the request thread, and
 * {@code PlaceOrderCmd} runs it again on the engine thread against the engine's state, where only that result counts.
 * Success yields the holds, the warnings and the pre-trade estimate.
 *
 * <p>Every Phase 1 order type is accepted (§11.1): MARKET, LIMIT, STOP, STOP_LIMIT and TRAILING_STOP.
 */
@Component
public class OrderValidator {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int MAX_RATIONALE = 1000;

  /** Account state the checks need (§10.2 balances, open orders, the position on this symbol). */
  public record AccountSnapshot(long accountId, AccountRestriction restriction, Balances balances, int openOrders,
                                BigDecimal positionQty, BigDecimal reservedSellQty) {
    BigDecimal available() {
      BigDecimal pos = positionQty == null ? Qty.ZERO : positionQty;
      return pos.subtract(reservedSellQty == null ? Qty.ZERO : reservedSellQty).max(Qty.ZERO);
    }
  }

  /**
   * Market state: the asset (null = unknown symbol), a corporate-action hold, the live quote view and the reference
   * price of checks 19–20 (consolidated or IEX last, else the previous close; split-adjusted by WS2).
   */
  public record MarketSnapshot(Asset asset, boolean corporateActionHold, QuoteView quote, BigDecimal reference) {}

  /**
   * @param holdCreditBack   a replaced order's hold, credited back to buying power (§11.6); zero otherwise
   * @param gfvRisk          GfvService predicts a good-faith violation on {@code sessionDate} (§10.4)
   * @param sessionRejection {@code SESSION_NOT_ALLOWED} from {@link SessionOrderMatrix#accept} (a trailing stop
   *                         outside REGULAR), thrown at check 18 so the checks before it win; null otherwise
   */
  public record Input(NormalizedOrder order, PlayerKind kind, boolean botsRequireRationale, MarketSnapshot market,
                      AccountSnapshot account, SessionOrderMatrix.Acceptance acceptance, LocalDate sessionDate,
                      boolean gfvRisk, BigDecimal holdCreditBack, Instant now, ApiException sessionRejection) {

    public Input(NormalizedOrder order, PlayerKind kind, boolean botsRequireRationale, MarketSnapshot market,
                 AccountSnapshot account, SessionOrderMatrix.Acceptance acceptance, LocalDate sessionDate,
                 boolean gfvRisk, BigDecimal holdCreditBack, Instant now) {
      this(order, kind, botsRequireRationale, market, account, acceptance, sessionDate, gfvRisk, holdCreditBack, now,
          null);
    }
  }

  /** A valid order: what to reserve, the warnings and the estimate. */
  public record Result(BigDecimal reservedCash, BigDecimal reservedQty, List<Warning> warnings, EstimateDto estimate,
                       BigDecimal buyingPowerBefore, BigDecimal buyingPowerAfter) {}

  private final RulesProperties rules;
  private final HoldCalculator holds;
  private final FeeCalculator fees;

  public OrderValidator(RulesProperties rules, HoldCalculator holds, FeeCalculator fees) {
    this.rules = rules;
    this.holds = holds;
    this.fees = fees;
  }

  /** Structural checks that are not order rules (missing members): 400 {@code VALIDATION_FAILED}. */
  public static void requireShape(NormalizedOrder o) {
    if (o.symbol() == null || o.symbol().isEmpty()) {
      throw ApiException.validation("symbol", "symbol is required");
    }
    if (o.side() == null) {
      throw ApiException.validation("side", "side is required (BUY or SELL)");
    }
    if (o.type() == null) {
      throw ApiException.validation("type", "type is required (MARKET, LIMIT, STOP, STOP_LIMIT or TRAILING_STOP)");
    }
  }

  public Result validate(Input in) {
    NormalizedOrder o = in.order();
    requireShape(o);
    List<Warning> warnings = new ArrayList<>(in.acceptance().warnings());
    boolean buy = o.side() == Side.BUY;

    // 7: rationale
    if (o.rationale() != null && o.rationale().length() > MAX_RATIONALE) {
      throw ApiException.of(ErrorCode.RATIONALE_REQUIRED, "rationale must be at most 1000 characters.")
          .withField("rationale", ErrorCode.RATIONALE_REQUIRED, "1–1000 characters");
    }
    if (in.kind() == PlayerKind.AI && in.botsRequireRationale() && o.rationale() == null) {
      throw ApiException.of(ErrorCode.RATIONALE_REQUIRED, "AI players must send a rationale with every order.")
          .withHint("Add \"rationale\": one or two sentences on why you are placing this order.")
          .withField("rationale", ErrorCode.RATIONALE_REQUIRED, "1–1000 characters");
    }

    // 8: symbol
    Asset asset = in.market().asset();
    if (asset == null) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol " + o.symbol() + ".")
          .withHint("Search with GET /api/v1/assets/search?q=…")
          .withField("symbol", ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol");
    }
    if (!asset.tradable() || !asset.active()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_TRADABLE, o.symbol() + " is not tradable.")
          .withField("symbol", ErrorCode.SYMBOL_NOT_TRADABLE, "Not tradable");
    }
    if (in.market().corporateActionHold()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_TRADABLE, o.symbol() + " is on hold.")
          .withHint("Pending corporate-action review; try again after the admin resolves it.")
          .withField("symbol", ErrorCode.SYMBOL_NOT_TRADABLE, "Corporate-action hold");
    }

    // 9: exactly one of qty / notional
    if ((o.qty() == null) == (o.notional() == null)) {
      throw ApiException.of(ErrorCode.QTY_OR_NOTIONAL_REQUIRED, "Send exactly one of qty or notional.")
          .withHint("qty is a number of shares (\"2\" or \"0.5\"); notional is dollars (\"125.00\", market orders).")
          .withField(o.qty() == null ? "qty" : "notional", ErrorCode.QTY_OR_NOTIONAL_REQUIRED, "Exactly one");
    }

    // 10: quantity
    if (o.qty() != null && (o.qty().signum() <= 0 || o.qty().compareTo(rules.maxOrderQty()) > 0
        || !Money.hasAtMostDecimals(o.qty(), Qty.SCALE))) {
      throw ApiException.of(ErrorCode.INVALID_QTY, "qty must be > 0, at most " + rules.maxOrderQty().toPlainString()
              + " and have at most 5 decimal places.")
          .withField("qty", ErrorCode.INVALID_QTY, "0 < qty ≤ 200000, ≤ 5 dp");
    }

    boolean fractional = o.fractional();
    // 11–13: fractional / notional eligibility (D7)
    if (fractional && !asset.fractionable()) {
      throw ApiException.of(ErrorCode.NOT_FRACTIONABLE, o.symbol() + " does not support fractional or dollar orders.")
          .withHint("Use a whole number of shares.")
          .withField(o.notional() != null ? "notional" : "qty", ErrorCode.NOT_FRACTIONABLE, "Whole shares only");
    }
    boolean fractionalLimitOk = rules.fractional().allowLimit() && o.type() == OrderType.LIMIT && o.notional() == null;
    if (fractional && o.type() != OrderType.MARKET && !fractionalLimitOk) {
      throw ApiException.of(ErrorCode.FRACTIONAL_MARKET_ONLY, "Fractional and dollar orders must be market orders.")
          .withField("type", ErrorCode.FRACTIONAL_MARKET_ONLY, "MARKET only");
    }
    if (fractional && (o.timeInForce() == TimeInForce.GTC || o.extendedHours())) {
      throw ApiException.of(ErrorCode.FRACTIONAL_DAY_ONLY,
              "Fractional and dollar orders are DAY only and execute in regular hours.")
          .withField(o.extendedHours() ? "extendedHours" : "timeInForce", ErrorCode.FRACTIONAL_DAY_ONLY, "DAY, RTH");
    }

    // 14: notional minimum
    if (o.notional() != null) {
      Money.require("notional", o.notional());
      BigDecimal min = buy ? rules.fractional().minBuyNotional() : rules.fractional().minSellNotional();
      if (o.notional().compareTo(min) < 0) {
        throw ApiException.of(ErrorCode.NOTIONAL_TOO_SMALL, "The minimum " + (buy ? "buy" : "sell") + " notional is $"
                + min.toPlainString() + ".")
            .withField("notional", ErrorCode.NOTIONAL_TOO_SMALL, "At least " + min.toPlainString());
      }
    } else if (buy && fractional && o.qty() != null) {
      // §11.1: the $1.00 buy minimum also binds a fractional quantity, valued at the touch (else the reference)
      QuoteView q14 = in.market().quote();
      BigDecimal px = q14 == null ? null : positive(q14.ask());
      px = px != null ? px : positive(in.market().reference());
      BigDecimal min = rules.fractional().minBuyNotional();
      if (px != null && o.qty().multiply(px).compareTo(min) < 0) {
        throw ApiException.of(ErrorCode.NOTIONAL_TOO_SMALL, "A fractional buy must be worth at least $"
                + min.toPlainString() + "; " + display(o.qty()) + " " + o.symbol() + " is about $"
                + Rounding.PRINCIPAL_BUY.multiply(o.qty(), px).toPlainString() + ".")
            .withHint("Buy a larger quantity, or send a notional of at least \"" + min.toPlainString() + "\".")
            .withField("qty", ErrorCode.NOTIONAL_TOO_SMALL, "Worth at least $" + min.toPlainString());
      }
    }

    // 15: prices required / allowed by type
    OrderType type = o.type();
    if (o.limitPrice() != null && !OrderTypes.usesLimit(type)) {
      throw priceNotAllowed("limitPrice", type, type == OrderType.MARKET ? "Remove it, or use type LIMIT"
          : "Remove it, or use type STOP_LIMIT");
    }
    if (o.stopPrice() != null && !OrderTypes.usesStop(type)) {
      throw priceNotAllowed("stopPrice", type, type == OrderType.TRAILING_STOP
          ? "A trailing stop's stop is computed from trailAmount or trailPercent" : "Remove it, or use type STOP");
    }
    if ((o.trailAmount() != null || o.trailPercent() != null) && !OrderTypes.usesTrail(type)) {
      throw priceNotAllowed(o.trailAmount() != null ? "trailAmount" : "trailPercent", type,
          "Remove it, or use type TRAILING_STOP");
    }
    if (OrderTypes.usesLimit(type) && o.limitPrice() == null) {
      throw ApiException.of(ErrorCode.LIMIT_PRICE_REQUIRED, type + " orders need a limitPrice.")
          .withField("limitPrice", ErrorCode.LIMIT_PRICE_REQUIRED, "Required");
    }
    if (OrderTypes.usesStop(type) && o.stopPrice() == null) {
      throw ApiException.of(ErrorCode.STOP_PRICE_REQUIRED, type + " orders need a stopPrice (the trigger price).")
          .withField("stopPrice", ErrorCode.STOP_PRICE_REQUIRED, "Required");
    }
    if (OrderTypes.usesTrail(type) && (o.trailAmount() == null) == (o.trailPercent() == null)) {
      throw ApiException.of(ErrorCode.TRAIL_REQUIRED, "TRAILING_STOP orders need exactly one of trailAmount "
              + "(dollars) or trailPercent (0 < p < 100).")
          .withHint("For example \"trailPercent\": \"5\" trails 5% behind the best price since the order started.")
          .withField(o.trailAmount() != null ? "trailPercent" : "trailAmount", ErrorCode.TRAIL_REQUIRED,
              "Exactly one of trailAmount and trailPercent");
    }

    // 16: tick (and positive prices)
    BigDecimal ref = in.market().reference();
    BigDecimal limit = null;
    if (o.limitPrice() != null) {
      if (o.limitPrice().signum() <= 0) {
        throw ApiException.validation("limitPrice", "limitPrice must be > 0");
      }
      limit = Prices.requireOnTick("limitPrice", o.limitPrice());
    }
    BigDecimal stop = null;
    if (o.stopPrice() != null) {
      if (o.stopPrice().signum() <= 0) {
        throw ApiException.validation("stopPrice", "stopPrice must be > 0");
      }
      stop = Prices.requireOnTick("stopPrice", o.stopPrice());
    }
    if (o.trailAmount() != null) {
      requireTrailAmountOnTick(o.trailAmount(), ref);
    }
    if (o.trailPercent() != null && (o.trailPercent().signum() <= 0
        || o.trailPercent().compareTo(HUNDRED) >= 0 || !Money.hasAtMostDecimals(o.trailPercent(), 4))) {
      throw ApiException.validation("trailPercent", "trailPercent must be > 0 and < 100, with at most 4 decimals");
    }

    // 17: extended hours
    if (o.extendedHours() && type != OrderType.LIMIT) {
      throw ApiException.of(ErrorCode.EXT_HOURS_LIMIT_ONLY, "Extended-hours orders must be LIMIT orders.")
          .withField("extendedHours", ErrorCode.EXT_HOURS_LIMIT_ONLY, "LIMIT only");
    }

    // 18: time in force, and the session a trailing stop may be entered in (§11.3)
    if ((type == OrderType.MARKET || type == OrderType.TRAILING_STOP) && o.timeInForce() == TimeInForce.GTC) {
      throw ApiException.of(ErrorCode.TIF_NOT_ALLOWED, type + " orders are DAY only.")
          .withField("timeInForce", ErrorCode.TIF_NOT_ALLOWED, "DAY");
    }
    if (in.sessionRejection() != null) {
      throw in.sessionRejection();
    }

    // 19: a stop on the right side of the market (it would trigger at once otherwise)
    if (stop != null && ref != null && ref.signum() > 0) {
      boolean wrongSide = buy ? stop.compareTo(ref) <= 0 : stop.compareTo(ref) >= 0;
      if (wrongSide) {
        throw ApiException.of(ErrorCode.STOP_PRICE_WRONG_SIDE, "A " + (buy ? "buy" : "sell") + " stop must be "
                + (buy ? "above" : "below") + " the market: stopPrice " + stop.toPlainString() + ", reference "
                + ref.toPlainString() + ".")
            .withHint(buy ? "A buy stop triggers when the price rises to it; to buy below the market use a LIMIT."
                : "A sell stop triggers when the price falls to it; to sell above the market use a LIMIT.")
            .withField("stopPrice", ErrorCode.STOP_PRICE_WRONG_SIDE, (buy ? "Above " : "Below ")
                + ref.toPlainString());
      }
    }

    // 20: limit or stop far from the reference (a trailing stop's starting stop, ref ∓ trail, counts as its stop)
    if (ref != null && ref.signum() > 0) {
      checkDistance("limitPrice", limit, ref, warnings);
      checkDistance("stopPrice", stop, ref, warnings);
      if (OrderTypes.usesTrail(type)) {
        BigDecimal t = HoldCalculator.trail(o, ref);
        checkDistance(o.trailAmount() != null ? "trailAmount" : "trailPercent", buy ? ref.add(t) : ref.subtract(t),
            ref, warnings);
      }
    }

    // 21: account restriction
    AccountSnapshot acc = in.account();
    if (acc.restriction() == AccountRestriction.FROZEN
        || (buy && acc.restriction() == AccountRestriction.LIQUIDATION_ONLY)) {
      throw ApiException.of(ErrorCode.ACCOUNT_RESTRICTED, "The account is restricted (" + acc.restriction() + ")"
              + (buy ? "; buys are not allowed." : "."))
          .withHint(acc.restriction() == AccountRestriction.LIQUIDATION_ONLY ? "Only sells are allowed." : null);
    }

    // 22: open orders
    if (acc.openOrders() >= rules.maxOpenOrders()) {
      throw ApiException.of(ErrorCode.TOO_MANY_OPEN_ORDERS, "At most " + rules.maxOpenOrders()
              + " open orders per account.")
          .withHint("Cancel some open orders first.");
    }

    QuoteView q = in.market().quote();
    BigDecimal ask = q == null ? null : positive(q.ask());
    BigDecimal bid = q == null ? null : positive(q.bid());
    BigDecimal reservedCash = Money.ZERO;
    BigDecimal reservedQty = Qty.ZERO;
    Balances bal = acc.balances();
    BigDecimal bpBefore = bal.buyingPower();
    BigDecimal bpAvailable = bpBefore.add(in.holdCreditBack() == null ? Money.ZERO : in.holdCreditBack());

    if (!buy) {
      // 23, 24: position
      if (acc.positionQty() == null || acc.positionQty().signum() <= 0) {
        throw ApiException.of(ErrorCode.SHORT_SELLING_NOT_ALLOWED, "You hold no " + o.symbol()
                + "; short selling is not allowed.")
            .withField("side", ErrorCode.SHORT_SELLING_NOT_ALLOWED, "No position");
      }
      BigDecimal available = acc.available();
      if (available.signum() <= 0 || (o.qty() != null && o.qty().compareTo(available) > 0)) {
        throw ApiException.of(ErrorCode.INSUFFICIENT_POSITION, "Available to sell: " + display(available) + " "
                + o.symbol() + " (position " + display(acc.positionQty()) + ", reserved by open sells "
                + display(acc.reservedSellQty()) + ").")
            .withHint(available.signum() > 0 ? "Sell at most " + display(available) + " shares." : null)
            .withField("qty", ErrorCode.INSUFFICIENT_POSITION, "At most " + display(available));
      }
      reservedQty = holds.sellReservedQty(o, available, bid != null ? bid : ref);
    } else {
      // 25, 26: hold vs buying power
      BigDecimal hold = holds.buyHold(o, in.acceptance().queued(), ask, ref, in.sessionDate());
      if (hold == null) {
        throw ApiException.of(ErrorCode.MARKET_DATA_UNAVAILABLE, "No price is available for " + o.symbol()
                + " to size the buying-power hold.")
            .withHint("Use a LIMIT order or a notional (dollar) amount, or retry when a quote is available.");
      }
      if (hold.compareTo(bpAvailable) > 0) {
        throw insufficientBuyingPower(o, hold, bpAvailable, holds.holdPrice(o, in.acceptance().queued(), ask, ref));
      }
      reservedCash = hold;
    }

    // 27: good-faith violation
    if (!buy && in.gfvRisk()) {
      if (rules.gfv().requiresAck() && !o.acknowledges(NormalizedOrder.ACK_GFV_RISK)) {
        throw ApiException.of(ErrorCode.GFV_ACK_REQUIRED, "Selling now would cause a good-faith violation: the shares "
                + "were bought with unsettled funds.")
            .withHint("Wait until the funds settle, or resend with \"acknowledge\": [\"GFV_RISK\"].")
            .withField("acknowledge", ErrorCode.GFV_ACK_REQUIRED, "Add GFV_RISK");
      }
      warnings.add(Warning.of(WarningCode.GFV_RISK, "This sell will cause a good-faith violation."));
    }

    // warnings about the data and the fill
    if (q != null && q.quality() == DataQuality.SYNTHETIC) {
      warnings.add(Warning.of(WarningCode.SYNTHETIC_QUOTE, "The estimate uses a synthetic quote."));
    } else if (q != null && (q.quality() == DataQuality.STALE || q.quality() == DataQuality.NONE)) {
      warnings.add(Warning.of(WarningCode.STALE_QUOTE, "The estimate uses a stale or missing quote."));
    }
    if (o.extendedHours() && inIexDarkWindow(in.now())) {
      warnings.add(Warning.of(WarningCode.EXT_HOURS_DATA_GAP,
          "No live IEX quotes 04:00–08:00 and 17:00–20:00 ET; extended-hours fills need consolidated prints."));
    }
    Long shown = q == null ? null : (buy ? q.askSize() : q.bidSize());
    if (!fractional && o.qty() != null && shown != null && shown > 0
        && o.qty().compareTo(BigDecimal.valueOf(shown)) > 0) {
      warnings.add(Warning.of(WarningCode.PARTIAL_FILL_LIKELY, "Quantity exceeds the displayed size of " + shown
          + "; the order may fill in parts."));
    }

    EstimateDto estimate = estimate(o, limit, ask, bid, ref, q, reservedCash, acc.available(), in.sessionDate());
    BigDecimal bpAfter = buy ? bpBefore.subtract(reservedCash) : bpBefore;
    return new Result(reservedCash, reservedQty, List.copyOf(warnings), estimate, bpBefore, bpAfter);
  }

  private static ApiException priceNotAllowed(String field, OrderType type, String fix) {
    return ApiException.of(ErrorCode.PRICE_NOT_ALLOWED, field + " is not used by " + type + " orders.")
        .withField(field, ErrorCode.PRICE_NOT_ALLOWED, fix);
  }

  /**
   * A trail amount is a price distance: positive, at most 4 decimals and on the tick of the stock's price ($0.01 at
   * or above $1.00, $0.0001 below; §6.2); without a reference price, on the tick of the amount itself.
   */
  static void requireTrailAmountOnTick(BigDecimal amount, BigDecimal ref) {
    if (amount.signum() <= 0) {
      throw ApiException.validation("trailAmount", "trailAmount must be > 0");
    }
    BigDecimal tick = Prices.tick(ref != null && ref.signum() > 0 ? ref : amount);
    if (!Money.hasAtMostDecimals(amount, Prices.SCALE) || amount.remainder(tick).signum() != 0) {
      throw ApiException.of(ErrorCode.INVALID_TICK, "trailAmount " + amount.toPlainString() + " is not on the "
              + tick.toPlainString() + " tick")
          .withField("trailAmount", ErrorCode.INVALID_TICK, "A multiple of " + tick.toPlainString());
    }
  }

  /** Check 20: more than {@code priceTooFarPct} from the reference is rejected; more than 5% warns. */
  private void checkDistance(String field, BigDecimal price, BigDecimal ref, List<Warning> warnings) {
    if (price == null) {
      return;
    }
    BigDecimal pct = price.subtract(ref).abs().multiply(HUNDRED).divide(ref, 4, RoundingMode.HALF_UP);
    String shown = pct.setScale(1, RoundingMode.HALF_UP).toPlainString();
    if (pct.compareTo(rules.priceTooFarPct()) > 0) {
      String what = field.startsWith("trail") ? "The trailing stop would start at " + price.setScale(Prices.SCALE,
          RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + ", " : field + " " + price.toPlainString()
          + " is ";
      throw ApiException.of(ErrorCode.PRICE_TOO_FAR, what + shown + "% from the reference " + ref.toPlainString()
              + ".")
          .withHint("Limit and stop prices must be within " + rules.priceTooFarPct().toPlainString()
              + "% of the market.")
          .withField(field, ErrorCode.PRICE_TOO_FAR, "Too far from " + ref.toPlainString());
    }
    if (pct.compareTo(rules.fatFingerWarnPct()) > 0) {
      String label = field.startsWith("trail") ? "The trailing stop's starting stop" : field;
      warnings.add(Warning.of(WarningCode.FAT_FINGER, label + " is " + shown + "% from the reference price "
          + ref.toPlainString() + "."));
    }
  }

  private ApiException insufficientBuyingPower(NormalizedOrder o, BigDecimal hold, BigDecimal bp, BigDecimal perShare) {
    String detail = "Order needs $" + hold.toPlainString() + "; buying power is $" + bp.toPlainString() + ".";
    String hint;
    if (o.notional() != null) {
      hint = bp.signum() > 0 ? "Try a notional amount of \"" + bp.toPlainString() + "\" or less." : null;
    } else {
      BigDecimal max = perShare == null || perShare.signum() <= 0 ? BigDecimal.ZERO
          : bp.max(BigDecimal.ZERO).divide(perShare, 0, RoundingMode.FLOOR);
      hint = "Max " + max.toPlainString() + " whole shares at $" + (perShare == null ? "?"
          : perShare.setScale(2, RoundingMode.HALF_UP).toPlainString()) + "."
          + (bp.compareTo(new BigDecimal("1.00")) >= 0 ? " Try a notional (dollar) market order of \""
              + bp.toPlainString() + "\" during regular hours." : "");
    }
    return ApiException.of(ErrorCode.INSUFFICIENT_BUYING_POWER, detail).withHint(hint)
        .withField(o.notional() != null ? "notional" : "qty", ErrorCode.INSUFFICIENT_BUYING_POWER,
            "Exceeds buying power");
  }

  /** The pre-trade estimate (§18.3 {@code Estimate}). */
  EstimateDto estimate(NormalizedOrder o, BigDecimal limit, BigDecimal ask, BigDecimal bid, BigDecimal ref,
                       QuoteView q, BigDecimal hold, BigDecimal available, LocalDate tradeDate) {
    boolean buy = o.side() == Side.BUY;
    BigDecimal touch = buy ? ask : bid;
    BigDecimal price = switch (o.type()) {
      case LIMIT -> touch != null && (buy ? touch.compareTo(limit) < 0 : touch.compareTo(limit) > 0) ? touch : limit;
      // a stop is expected to fill around its trigger; a stop-limit never beyond its limit
      case STOP -> o.stopPrice();
      case STOP_LIMIT -> buy ? o.stopPrice().min(limit) : o.stopPrice().max(limit);
      case TRAILING_STOP -> ref == null || ref.signum() <= 0 ? null : trailingStart(o, ref);
      default -> touch != null ? touch : ref;
    };
    BigDecimal qty = o.qty();
    if (qty == null && price != null && price.signum() > 0) {
      qty = buy ? notionalBuyQty(o.notional(), price, tradeDate)
          : Rounding.NOTIONAL_QTY.divide(o.notional(), price).min(available);
    }
    BigDecimal principal = null;
    Fees f = Fees.ZERO;
    if (qty != null && price != null) {
      principal = Rounding.principal(o.side(), qty, price);
      f = fees.estimate(o.side(), qty, principal, tradeDate);
    }
    return new EstimateDto(price == null ? null : Prices.normalize(price.setScale(Prices.SCALE, RoundingMode.HALF_UP)),
        qty == null ? null : qtyOut(qty), principal, f.toDto(), hold, q == null ? null : q.asOf(),
        q == null ? DataQuality.NONE : q.quality());
  }

  /** A trailing stop's starting stop: {@code TICK_DOWN(ref − trail)} (sell) or {@code TICK_UP(ref + trail)} (buy). */
  static BigDecimal trailingStart(NormalizedOrder o, BigDecimal ref) {
    BigDecimal t = HoldCalculator.trail(o, ref);
    if (o.side() == Side.BUY) {
      return Rounding.TICK_UP.apply(ref.add(t));
    }
    BigDecimal s = ref.subtract(t);
    return s.signum() <= 0 ? null : Rounding.TICK_DOWN.apply(s);
  }

  /**
   * The all-in notional buy quantity (D7): {@code q0 = floor5(notional / price)}, then
   * {@code qty = floor5((notional − estFees(q0)) / price)}, so principal + fees ≤ notional.
   */
  public BigDecimal notionalBuyQty(BigDecimal notional, BigDecimal price, LocalDate tradeDate) {
    BigDecimal q0 = Rounding.NOTIONAL_QTY.divide(notional, price);
    BigDecimal est = fees.estimate(Side.BUY, q0, Rounding.PRINCIPAL_BUY.multiply(q0, price), tradeDate).total();
    return Rounding.NOTIONAL_QTY.divide(notional.subtract(est), price).max(Qty.ZERO);
  }

  /** IEX is dark 04:00–08:00 and 17:00–20:00 ET (§9.3). */
  static boolean inIexDarkWindow(Instant now) {
    LocalTime t = Et.time(now);
    return (!t.isBefore(LocalTime.of(4, 0)) && t.isBefore(LocalTime.of(8, 0)))
        || (!t.isBefore(LocalTime.of(17, 0)) && t.isBefore(LocalTime.of(20, 0)));
  }

  private static BigDecimal positive(BigDecimal v) {
    return v == null || v.signum() <= 0 ? null : v;
  }

  static String display(BigDecimal qty) {
    return qty == null ? "0" : qtyOut(qty).toPlainString();
  }

  /** Quantities on the wire: trailing zeros stripped ({@code "0.95274"}, {@code "10"}, {@code "0"}). */
  public static BigDecimal qtyOut(BigDecimal qty) {
    if (qty == null) {
      return null;
    }
    BigDecimal s = qty.stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0) : s;
  }
}
