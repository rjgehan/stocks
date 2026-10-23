package com.stocks.trading.order;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Set;

/**
 * The group-only rules of §11.9 (pure): the shape of each group type (legs present and absent, leg types and sides,
 * whole shares, BUY entries in wave A) and the bracket price rules. Each failure is {@code ORDER_GROUP_INVALID} (422)
 * with the offending field ({@code takeProfit.limitPrice}, {@code entry.type}, …). The ordinary order checks of each
 * leg (§11.2 checks 7–27) run afterwards in {@link GroupEvaluation}.
 */
public final class GroupRules {

  /** The bracket's take profit and stop loss must be at least this far apart, relative to the entry price. */
  public static final BigDecimal MIN_BRACKET_GAP_PCT = new BigDecimal("0.1");

  static final Set<OrderType> ENTRY_TYPES = EnumSet.of(OrderType.MARKET, OrderType.LIMIT, OrderType.STOP,
      OrderType.STOP_LIMIT);
  static final Set<OrderType> BRACKET_ENTRY_TYPES = EnumSet.of(OrderType.MARKET, OrderType.LIMIT);
  static final Set<OrderType> SECONDARY_TYPES = EnumSet.of(OrderType.LIMIT, OrderType.STOP, OrderType.STOP_LIMIT);
  static final Set<OrderType> STOP_LOSS_TYPES = EnumSet.of(OrderType.STOP, OrderType.STOP_LIMIT);

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private GroupRules() {}

  /** The entry types a group type accepts ({@code GET /rules} {@code orders.groups}). */
  public static Set<OrderType> entryTypes(OrderGroupType type) {
    return switch (type) {
      case OCO -> Set.of();
      case BRACKET -> BRACKET_ENTRY_TYPES;
      case OTO, OTOCO -> ENTRY_TYPES;
    };
  }

  /** Check 1 of §11.9: the group's shape. */
  public static void requireShape(NormalizedGroup g, BigDecimal maxQty) {
    if (g.type() == null) {
      throw invalid("type", "type is required: OCO, OTO, OTOCO or BRACKET.");
    }
    if (g.symbol() == null || g.symbol().isEmpty()) {
      throw ApiException.validation("symbol", "symbol is required");
    }
    if (OptionSymbol.isOption(g.symbol())) {
      // §30: long options are single MARKET or LIMIT orders; brackets, OCO and spreads are not offered
      throw invalid("symbol", "Group orders (OCO, OTO, OTOCO, BRACKET) are not available for options; send single "
          + "MARKET or LIMIT orders.");
    }
    if (g.qty() == null) {
      throw invalid("qty", "qty is required: a whole number of shares for every leg.");
    }
    if (Crypto.isCrypto(g.symbol())) {
      // a crypto pair's legs take fractional quantities (§29)
      if (g.qty().signum() <= 0 || !Money.hasAtMostDecimals(g.qty(), Qty.SCALE)
          || g.qty().compareTo(Crypto.MAX_ORDER_QTY) > 0) {
        throw invalid("qty", "Crypto group orders take a quantity above 0 with at most 5 decimals, up to "
            + Crypto.MAX_ORDER_QTY.toPlainString() + ".");
      }
    } else if (g.qty().signum() <= 0 || !Money.hasAtMostDecimals(g.qty(), 0) || g.qty().compareTo(maxQty) > 0) {
      throw invalid("qty", "Group orders take a whole number of shares from 1 to " + maxQty.toPlainString() + ".");
    }
    if (g.type() != OrderGroupType.OCO && g.side() != Side.BUY) {
      throw invalid("side", g.type() + " entries buy; a sell-first " + g.type() + " is not available yet.")
          .withHint("To protect shares you hold, use an OCO (take profit + stop loss) with side SELL.");
    }
    for (OrderGroupRole role : OrderGroupRole.values()) {
      boolean wanted = g.roles().contains(role);
      NormalizedGroup.Leg leg = g.leg(role);
      if (wanted && leg == null) {
        throw invalid(field(role), g.type() + " needs " + label(role).toLowerCase(java.util.Locale.ROOT) + ".");
      }
      if (!wanted && leg != null) {
        throw invalid(field(role), g.type() + " has no " + label(role).toLowerCase(java.util.Locale.ROOT)
            + " leg; remove " + field(role) + ".");
      }
    }
    for (OrderGroupRole role : g.roles()) {
      OrderType t = g.leg(role).type();
      Set<OrderType> allowed = switch (role) {
        case ENTRY -> entryTypes(g.type());
        case TAKE_PROFIT -> Set.of(OrderType.LIMIT);
        case STOP_LOSS -> STOP_LOSS_TYPES;
        case SECONDARY -> SECONDARY_TYPES;
      };
      if (t == null) {
        throw invalid(field(role) + ".type", label(role) + " needs a type (" + names(allowed) + ").");
      }
      if (!allowed.contains(t)) {
        throw invalid(field(role) + ".type", label(role) + " of a " + g.type() + " must be " + names(allowed)
            + ", not " + t + ".");
      }
    }
  }

  /**
   * The bracket price rules (§11.9): {@code takeProfit.limitPrice > entryPrice > stopLoss.stopPrice} and
   * {@code takeProfit − stopLoss ≥ 0.1% × entryPrice}. Skipped when a price is missing (the leg's check 15 reports
   * it).
   */
  public static void requireBracketPrices(NormalizedGroup g, BigDecimal entryPrice) {
    if (g.type() != OrderGroupType.BRACKET || entryPrice == null) {
      return;
    }
    BigDecimal tp = g.takeProfit().limitPrice();
    BigDecimal sl = g.stopLoss().stopPrice();
    if (tp != null && tp.compareTo(entryPrice) <= 0) {
      throw invalid("takeProfit.limitPrice", "The take profit (" + plain(tp) + ") must be above the entry price ("
          + plain(entryPrice) + ").");
    }
    if (sl != null && sl.compareTo(entryPrice) >= 0) {
      throw invalid("stopLoss.stopPrice", "The stop loss (" + plain(sl) + ") must be below the entry price ("
          + plain(entryPrice) + ").");
    }
    if (tp != null && sl != null) {
      BigDecimal minGap = entryPrice.multiply(MIN_BRACKET_GAP_PCT).divide(HUNDRED, 4, RoundingMode.CEILING);
      if (tp.subtract(sl).compareTo(minGap) < 0) {
        throw invalid("stopLoss.stopPrice", "The take profit and the stop loss must be at least "
            + MIN_BRACKET_GAP_PCT.toPlainString() + "% of the entry price ($" + plain(minGap) + ") apart.");
      }
    }
  }

  /** The request field of a leg ({@code takeProfit}). */
  public static String field(OrderGroupRole role) {
    return switch (role) {
      case ENTRY -> "entry";
      case TAKE_PROFIT -> "takeProfit";
      case STOP_LOSS -> "stopLoss";
      case SECONDARY -> "secondary";
    };
  }

  /** A human label of a leg ("Take profit"). */
  public static String label(OrderGroupRole role) {
    return switch (role) {
      case ENTRY -> "Entry";
      case TAKE_PROFIT -> "Take profit";
      case STOP_LOSS -> "Stop loss";
      case SECONDARY -> "Then order";
    };
  }

  static ApiException invalid(String field, String message) {
    return ApiException.of(ErrorCode.ORDER_GROUP_INVALID, message)
        .withField(field, ErrorCode.ORDER_GROUP_INVALID, message);
  }

  private static String names(Set<OrderType> types) {
    return String.join(" or ", EnumSet.copyOf(types).stream().map(Enum::name).toList());
  }

  private static String plain(BigDecimal v) {
    return v.stripTrailingZeros().scale() < 2 ? v.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        : v.stripTrailingZeros().toPlainString();
  }
}
