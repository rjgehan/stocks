package com.stocks.alerts;

import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.contract.marketdata.ExtendedLine;
import com.stocks.contract.marketdata.QuoteView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The pure rules of price alerts (§19.1): what counts as a live price, when a condition holds, when a repeating alert
 * re-arms, threshold validation and the notification text. No I/O, no clock: every input is a parameter.
 */
public final class AlertRules {

  /** Quote qualities that may evaluate an alert. Stale, synthetic ("Est."), delayed and empty quotes never do. */
  public static final Set<DataQuality> LIVE_QUALITIES =
      EnumSet.of(DataQuality.REALTIME_CONSOLIDATED, DataQuality.REALTIME_SINGLE_VENUE, DataQuality.SIMULATED);

  /** A price threshold must be below this. */
  public static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
  /** A change threshold must be above this (−100%). */
  public static final BigDecimal MIN_CHANGE = BigDecimal.ONE.negate();
  /** A change threshold must be at most this (+1000%). */
  public static final BigDecimal MAX_CHANGE = BigDecimal.TEN;
  public static final int PRICE_DECIMALS = 4;
  public static final int CHANGE_DECIMALS = 6;
  /** Scale of a computed change fraction (as {@code Quote.changePct}). */
  public static final int CHANGE_SCALE = 10;

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final String MINUS = "−";

  private AlertRules() {}

  /**
   * The live price an alert evaluates against.
   *
   * @param price     the price of the session's live line
   * @param changePct the change from the previous regular close as a fraction, or null when unknown
   * @param at        when that price printed
   */
  public record Live(BigDecimal price, BigDecimal changePct, Instant at) {}

  /**
   * The live price of {@code quote} in {@code session}, or empty when it may not evaluate (or re-arm) an alert:
   * REGULAR uses {@code last} at {@code lastAt}; PRE and POST the extended line (the main line is a close, not live);
   * CLOSED never. The quote must be of a {@link #LIVE_QUALITIES live quality}, not halted, and its print at most
   * {@code maxAge} old.
   */
  public static Optional<Live> live(QuoteView quote, SessionType session, Instant now, Duration maxAge) {
    if (quote == null || quote.halted() || quote.quality() == null || !LIVE_QUALITIES.contains(quote.quality())
        || session == null) {
      return Optional.empty();
    }
    BigDecimal price;
    Instant at;
    BigDecimal changePct;
    switch (session) {
      case REGULAR -> {
        price = quote.last();
        at = quote.lastAt();
        changePct = quote.changePct() != null ? quote.changePct() : change(price, quote.prevClose());
      }
      case PRE, POST -> {
        ExtendedLine ext = quote.extended();
        if (ext == null || ext.session() != session) {
          return Optional.empty();
        }
        price = ext.price();
        at = ext.at();
        // PRE: the extended line is measured against the last regular close (the main line's price), which is the
        // reference itself. POST: it is measured against today's close, so the day's change comes from prevClose.
        if (session == SessionType.PRE) {
          changePct = ext.changePct() != null ? ext.changePct() : change(price, quote.last());
        } else {
          changePct = change(price, quote.prevClose());
        }
      }
      default -> {
        return Optional.empty();
      }
    }
    if (price == null || price.signum() <= 0 || at == null) {
      return Optional.empty();
    }
    Duration age = Duration.between(at, now);
    if (age.compareTo(maxAge) > 0) {
      return Optional.empty();
    }
    return Optional.of(new Live(price, changePct, at));
  }

  /** (price − reference) ÷ reference at scale 10, HALF_EVEN; null when either is missing or the reference is 0. */
  public static BigDecimal change(BigDecimal price, BigDecimal reference) {
    if (price == null || reference == null || reference.signum() == 0) {
      return null;
    }
    return price.subtract(reference).divide(reference, CHANGE_SCALE, RoundingMode.HALF_EVEN);
  }

  /** Whether the condition holds on {@code live} (a change condition never holds without a known change). */
  public static boolean holds(AlertCondition condition, BigDecimal threshold, Live live) {
    BigDecimal value = valueOf(condition, live);
    if (value == null) {
      return false;
    }
    int cmp = value.compareTo(threshold);
    return condition.isAbove() ? cmp >= 0 : cmp <= 0;
  }

  /**
   * Whether a repeating alert that fired re-arms on {@code live}: the value is back on the other side of the
   * threshold by {@code band} (a fraction of the price for price alerts, points of change for change alerts).
   * Hysteresis keeps an alert from chattering around its threshold.
   */
  public static boolean rearms(AlertCondition condition, BigDecimal threshold, Live live, BigDecimal band) {
    BigDecimal value = valueOf(condition, live);
    if (value == null) {
      return false;
    }
    BigDecimal level;
    if (condition.isPrice()) {
      BigDecimal factor = condition.isAbove() ? BigDecimal.ONE.subtract(band) : BigDecimal.ONE.add(band);
      level = threshold.multiply(factor);
    } else {
      level = condition.isAbove() ? threshold.subtract(band) : threshold.add(band);
    }
    int cmp = value.compareTo(level);
    return condition.isAbove() ? cmp <= 0 : cmp >= 0;
  }

  private static BigDecimal valueOf(AlertCondition condition, Live live) {
    return condition.isPrice() ? live.price() : live.changePct();
  }

  /**
   * A valid threshold for {@code condition}, without trailing zeros beyond its meaning; {@code VALIDATION_FAILED}
   * (field {@code threshold}) otherwise. A price is &gt; 0, below 1,000,000, at most 4 decimals; a change is a fraction
   * −1 &lt; t ≤ 10 with at most 6 decimals.
   */
  public static BigDecimal validThreshold(AlertCondition condition, BigDecimal threshold) {
    if (condition == null) {
      throw ApiException.validation("condition", "condition is required.");
    }
    if (threshold == null) {
      throw ApiException.validation("threshold", "threshold is required.");
    }
    BigDecimal t = threshold.stripTrailingZeros();
    if (t.scale() < 0) {
      t = t.setScale(0, RoundingMode.UNNECESSARY);
    }
    if (condition.isPrice()) {
      if (t.signum() <= 0 || t.compareTo(MAX_PRICE) >= 0) {
        throw ApiException.validation("threshold", "A price threshold must be above 0 and below 1,000,000.");
      }
      if (t.scale() > PRICE_DECIMALS) {
        throw ApiException.validation("threshold", "A price threshold has at most 4 decimals.");
      }
    } else {
      if (t.compareTo(MIN_CHANGE) <= 0 || t.compareTo(MAX_CHANGE) > 0) {
        throw ApiException.validation("threshold",
            "A change threshold is a fraction above -1 (-100%) and at most 10 (+1000%), e.g. \"0.05\" for +5%.");
      }
      if (t.scale() > CHANGE_DECIMALS) {
        throw ApiException.validation("threshold", "A change threshold has at most 6 decimals (0.0001%).");
      }
    }
    return t;
  }

  // ------------------------------------------------------------------------------------------- text

  /** {@code $230.00}, or 4 decimals below $1. */
  public static String money(BigDecimal price) {
    if (price == null) {
      return "--";
    }
    int scale = price.abs().compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
    String s = price.abs().setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    return (price.signum() < 0 ? MINUS : "") + "$" + s;
  }

  /** A fraction as a signed percentage: {@code 0.0231} → {@code +2.31%}, {@code -0.03} → {@code −3.00%}. */
  public static String percent(BigDecimal fraction) {
    if (fraction == null) {
      return "--";
    }
    BigDecimal pct = fraction.multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
    String sign = pct.signum() > 0 ? "+" : pct.signum() < 0 ? MINUS : "";
    return sign + pct.abs().toPlainString() + "%";
  }

  /** The threshold as the UI shows it: a price or a signed percentage. */
  public static String thresholdText(AlertCondition condition, BigDecimal threshold) {
    return condition.isPrice() ? money(threshold) : percent(threshold);
  }

  /** "price at or above $230.00", "day change at or below −3.00%". */
  public static String describe(AlertCondition condition, BigDecimal threshold) {
    String what = condition.isPrice() ? "price" : "day change";
    String dir = condition.isAbove() ? "at or above" : "at or below";
    return what + " " + dir + " " + thresholdText(condition, threshold);
  }

  /** The notification title: {@code AAPL ≥ $230.00}, {@code AAPL day change ≤ −3.00%}. */
  public static String title(String symbol, AlertCondition condition, BigDecimal threshold) {
    String op = condition.isAbove() ? "≥" : "≤";
    return symbol + (condition.isPrice() ? "" : " day change") + " " + op + " " + thresholdText(condition, threshold);
  }

  /**
   * The notification body: {@code AAPL is at $230.15 (+2.31% today). Your alert: price at or above $230.00.} plus the
   * player's private note.
   */
  public static String body(String symbol, AlertCondition condition, BigDecimal threshold, Live live, String note) {
    StringBuilder b = new StringBuilder(symbol).append(" is at ").append(money(live.price()));
    if (live.changePct() != null) {
      b.append(" (").append(percent(live.changePct())).append(" today)");
    }
    b.append(". Your alert: ").append(describe(condition, threshold)).append('.');
    if (note != null && !note.isBlank()) {
      b.append(" Note: ").append(note.strip());
    }
    return b.toString();
  }
}
