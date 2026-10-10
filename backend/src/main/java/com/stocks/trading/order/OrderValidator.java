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
 * <p>Phase-1 scope of this build: MARKET and LIMIT. STOP, STOP_LIMIT and TRAILING_STOP are rejected with
 * {@code VALIDATION_FAILED} on {@code type} until the trigger evaluator lands.
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
   * @param holdCreditBack a replaced order's hold, credited back to buying power (§11.6); zero otherwise
   * @param gfvRisk        GfvService predicts a good-faith violation on {@code sessionDate} (§10.4)
   */
  public record Input(NormalizedOrder order, PlayerKind kind, boolean botsRequireRationale, MarketSnapshot market,
                      AccountSnapshot account, SessionOrderMatrix.Acceptance acceptance, LocalDate sessionDate,
                      boolean gfvRisk, BigDecimal holdCreditBack, Instant now) {}

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
      throw ApiException.validation("type", "type is required (MARKET or LIMIT)");
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
    }

    // 15: prices required / allowed by type
    if (o.type() != OrderType.MARKET && o.type() != OrderType.LIMIT) {
      throw ApiException.validation("type", o.type() + " orders are not available yet; use MARKET or LIMIT.");
    }
    if (o.stopPrice() != null || o.trailAmount() != null || o.trailPercent() != null) {
      String field = o.stopPrice() != null ? "stopPrice" : o.trailAmount() != null ? "trailAmount" : "trailPercent";
      throw ApiException.of(ErrorCode.PRICE_NOT_ALLOWED, field + " is not used by " + o.type() + " orders.")
          .withField(field, ErrorCode.PRICE_NOT_ALLOWED, "Remove it");
    }
    if (o.type() == OrderType.MARKET && o.limitPrice() != null) {
      throw ApiException.of(ErrorCode.PRICE_NOT_ALLOWED, "limitPrice is not used by MARKET orders.")
          .withField("limitPrice", ErrorCode.PRICE_NOT_ALLOWED, "Remove it, or use type LIMIT");
    }
    if (o.type() == OrderType.LIMIT && o.limitPrice() == null) {
      throw ApiException.of(ErrorCode.LIMIT_PRICE_REQUIRED, "LIMIT orders need a limitPrice.")
          .withField("limitPrice", ErrorCode.LIMIT_PRICE_REQUIRED, "Required");
    }

    // 16: tick
    BigDecimal limit = null;
    if (o.limitPrice() != null) {
      if (o.limitPrice().signum() <= 0) {
        throw ApiException.validation("limitPrice", "limitPrice must be > 0");
      }
      limit = Prices.requireOnTick("limitPrice", o.limitPrice());
    }

    // 17: extended hours
    if (o.extendedHours() && o.type() != OrderType.LIMIT) {
      throw ApiException.of(ErrorCode.EXT_HOURS_LIMIT_ONLY, "Extended-hours orders must be LIMIT orders.")
          .withField("extendedHours", ErrorCode.EXT_HOURS_LIMIT_ONLY, "LIMIT only");
    }

    // 18: time in force
    if (o.type() == OrderType.MARKET && o.timeInForce() == TimeInForce.GTC) {
      throw ApiException.of(ErrorCode.TIF_NOT_ALLOWED, "MARKET orders are DAY only.")
          .withField("timeInForce", ErrorCode.TIF_NOT_ALLOWED, "DAY");
    }

    // 20: limit far from the reference (19 applies to stops only)
    BigDecimal ref = in.market().reference();
    if (limit != null && ref != null && ref.signum() > 0) {
      BigDecimal pct = limit.subtract(ref).abs().multiply(HUNDRED).divide(ref, 4, RoundingMode.HALF_UP);
      if (pct.compareTo(rules.priceTooFarPct()) > 0) {
        throw ApiException.of(ErrorCode.PRICE_TOO_FAR, "limitPrice " + limit.toPlainString() + " is "
                + pct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "% from the reference " + ref.toPlainString()
                + ".")
            .withHint("Limit prices must be within " + rules.priceTooFarPct().toPlainString() + "% of the market.")
            .withField("limitPrice", ErrorCode.PRICE_TOO_FAR, "Too far from " + ref.toPlainString());
      }
      if (pct.compareTo(rules.fatFingerWarnPct()) > 0) {
        warnings.add(Warning.of(WarningCode.FAT_FINGER, "limitPrice is "
            + pct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "% from the reference price "
            + ref.toPlainString() + "."));
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
    BigDecimal price;
    if (o.type() == OrderType.LIMIT) {
      price = touch != null && (buy ? touch.compareTo(limit) < 0 : touch.compareTo(limit) > 0) ? touch : limit;
    } else {
      price = touch != null ? touch : ref;
    }
    BigDecimal qty = o.qty();
    if (qty == null && price != null && price.signum() > 0) {
      qty = buy ? notionalBuyQty(o.notional(), price, tradeDate)
          : Rounding.NOTIONAL_QTY.divide(o.notional(), price).min(available);
    }
    BigDecimal principal = null;
    Fees f = Fees.ZERO;
    if (qty != null && price != null) {
      principal = Rounding.PRINCIPAL.multiply(qty, price);
      f = fees.estimate(o.side(), qty, principal, tradeDate);
    }
    return new EstimateDto(price == null ? null : Prices.normalize(price.setScale(Prices.SCALE, RoundingMode.HALF_UP)),
        qty == null ? null : qtyOut(qty), principal, f.toDto(), hold, q == null ? null : q.asOf(),
        q == null ? DataQuality.NONE : q.quality());
  }

  /**
   * The all-in notional buy quantity (D7): {@code q0 = floor5(notional / price)}, then
   * {@code qty = floor5((notional − estFees(q0)) / price)}, so principal + fees ≤ notional.
   */
  public BigDecimal notionalBuyQty(BigDecimal notional, BigDecimal price, LocalDate tradeDate) {
    BigDecimal q0 = Rounding.NOTIONAL_QTY.divide(notional, price);
    BigDecimal est = fees.estimate(Side.BUY, q0, Rounding.PRINCIPAL.multiply(q0, price), tradeDate).total();
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
