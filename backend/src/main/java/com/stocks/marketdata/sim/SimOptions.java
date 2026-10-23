package com.stocks.marketdata.sim;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OptionRight;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.money.Prices;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.model.OptionQuote;
import com.stocks.contract.marketdata.model.Quote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * SIM option contracts (§30, §15.7): a pure function of the underlying's SIM quote, so option quotes move with the
 * stock and are exact under a fixed test clock. Prices are Black-Scholes values (no dividends, 4% rate, a volatility
 * per liquidity tier) quoted with a spread of 3% of the value (at least a cent a side), on the cent. Expirations are
 * the next six weekly Fridays and the next six monthly third Fridays (the previous trading day when a Friday is a
 * holiday); strikes are 12 steps either side of the money.
 */
final class SimOptions {

  static final String SOURCE = "simulated";
  static final double RATE = 0.04;
  static final int STRIKES_EACH_SIDE = 12;
  private static final double YEAR_SECONDS = 365.0 * 24 * 3600;
  private static final BigDecimal CENT = new BigDecimal("0.01");

  private SimOptions() {}

  static double volatility(LiquidityTier tier) {
    return switch (tier == null ? LiquidityTier.SMALL : tier) {
      case MEGA -> 0.25;
      case MID -> 0.35;
      case SMALL -> 0.50;
    };
  }

  /** The expirations listed on {@code today}: weekly Fridays for six weeks, monthly third Fridays for six months. */
  static List<LocalDate> expirations(MarketCalendar calendar, LocalDate today) {
    TreeSet<LocalDate> out = new TreeSet<>();
    LocalDate friday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    for (int w = 0; w < 6; w++) {
      out.add(tradingDayOnOrBefore(calendar, friday.plusWeeks(w)));
    }
    for (int m = 0; m <= 6; m++) {
      LocalDate first = today.withDayOfMonth(1).plusMonths(m);
      out.add(tradingDayOnOrBefore(calendar, first.with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY))));
    }
    out.removeIf(d -> d.isBefore(today));
    return List.copyOf(out);
  }

  private static LocalDate tradingDayOnOrBefore(MarketCalendar calendar, LocalDate d) {
    try {
      return calendar.tradingDayOnOrBefore(d);
    } catch (RuntimeException e) {
      return d;
    }
  }

  /** The strike step of a stock at {@code spot}. */
  static BigDecimal step(BigDecimal spot) {
    double s = spot.doubleValue();
    String step = s < 25 ? "0.5" : s < 50 ? "1" : s < 100 ? "2.5" : s < 250 ? "5" : s < 1000 ? "10" : "25";
    return new BigDecimal(step);
  }

  /** {@link #STRIKES_EACH_SIDE} strikes either side of the strike nearest {@code spot}, ascending, all positive. */
  static List<BigDecimal> strikes(BigDecimal spot) {
    BigDecimal step = step(spot);
    BigDecimal center = spot.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    List<BigDecimal> out = new ArrayList<>();
    for (int k = -STRIKES_EACH_SIDE; k <= STRIKES_EACH_SIDE; k++) {
      BigDecimal strike = center.add(step.multiply(BigDecimal.valueOf(k)));
      if (strike.signum() > 0) {
        out.add(strike.setScale(2, RoundingMode.UNNECESSARY));
      }
    }
    return out;
  }

  /** The contract's quote from the underlying's quote in force at {@code underlying.ts()}; empty without a mid. */
  static Optional<Quote> quote(OptionSymbol.Contract c, Quote underlying, LiquidityTier tier) {
    if (underlying == null || !underlying.twoSided() || underlying.ts() == null) {
      return Optional.empty();
    }
    double spot = underlying.bid().add(underlying.ask()).doubleValue() / 2;
    BigDecimal theo = SimMath.decimal6(value(c, spot, underlying.ts(), volatility(tier)));
    BigDecimal half = SimMath.decimal6(theo.doubleValue() * 0.015).max(CENT);
    BigDecimal bid = theo.subtract(half).setScale(2, RoundingMode.FLOOR).max(BigDecimal.ZERO);
    BigDecimal ask = theo.add(half).setScale(2, RoundingMode.CEILING).max(CENT);
    long size = 5 + Math.floorMod(c.symbol().hashCode(), 46);
    return Optional.of(new Quote(c.symbol(), bid.setScale(Prices.SCALE), bid.signum() > 0 ? size : 0,
        ask.setScale(Prices.SCALE), size, "SIM", "SIM", underlying.ts(), underlying.observedAt(), DataQuality.SIMULATED,
        SOURCE));
  }

  /** A chain row with deterministic volume and open interest that grow toward the money. */
  static OptionQuote row(OptionSymbol.Contract c, Quote quote, BigDecimal spot) {
    double moneyness = Math.abs(c.strike().doubleValue() / spot.doubleValue() - 1);
    double weight = Math.max(0.02, 1 - moneyness * 8);
    long seed = Math.floorMod(c.symbol().hashCode(), 1000);
    return new OptionQuote(c.symbol(), c.underlying(), c.expiration(), c.right(), c.strike(), quote, null,
        Math.round((50 + seed) * weight), Math.round((500 + seed * 9) * weight), null, null);
  }

  /** Black-Scholes value per share at {@code t}; intrinsic value at or after the expiration's 16:00 ET close. */
  static double value(OptionSymbol.Contract c, double spot, Instant t, double vol) {
    Instant close = Et.at(c.expiration(), LocalTime.of(16, 0));
    double years = Duration.between(t, close).toSeconds() / YEAR_SECONDS;
    double k = c.strike().doubleValue();
    if (years <= 0 || spot <= 0) {
      return Math.max(0, c.right() == OptionRight.CALL ? spot - k : k - spot);
    }
    double sd = vol * Math.sqrt(years);
    double d1 = (Math.log(spot / k) + (RATE + vol * vol / 2) * years) / sd;
    double d2 = d1 - sd;
    double discount = Math.exp(-RATE * years);
    return c.right() == OptionRight.CALL
        ? spot * cdf(d1) - k * discount * cdf(d2)
        : k * discount * cdf(-d2) - spot * cdf(-d1);
  }

  /** The standard normal CDF (Abramowitz–Stegun 7.1.26, error below 1.5e-7). */
  static double cdf(double x) {
    double z = Math.abs(x) / Math.sqrt(2);
    double t = 1 / (1 + 0.3275911 * z);
    double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
        * t * Math.exp(-z * z);
    return x >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
  }
}
