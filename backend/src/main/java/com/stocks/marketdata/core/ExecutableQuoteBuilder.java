package com.stocks.marketdata.core;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.SessionType;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Rounding;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.config.ExecQuoteRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The executable quote of §12.3 (anti-look-ahead), as a pure function of what the {@code QuoteBook} knows.
 *
 * <ol>
 *   <li>Not halted.</li>
 *   <li>Confirmed at or after {@code notBefore} (the order's {@code eligible_at}).</li>
 *   <li>Fresh: {@code now − confirmedAt} ≤ 15 s (REGULAR) / 120 s (PRE, POST); {@code now − quoteTs} ≤ 5 / 15 min.</li>
 *   <li>Quality ladder: (1) a consolidated quote (SIP or SIM), two-sided and not crossed; (2) the IEX top of book when
 *       two-sided, not crossed, within the tier's IEX spread limit and consistent with the consolidated prints of the
 *       last 10 s (clamped around a print at most 2 s old); (3) a SYNTHETIC quote around the freshest eligible print
 *       (consolidated first; the IEX last only when no usable quote exists at all); otherwise none, and the order
 *       waits with {@code AWAITING_QUOTE}.</li>
 * </ol>
 */
public final class ExecutableQuoteBuilder {

  /** Everything the builder looks at for one symbol. Prints are eligible prints, newest last. */
  public record Inputs(String symbol, LiquidityTier tier, boolean halted, Quote quote, Instant confirmedAt,
                       boolean consolidatedQuote, List<Trade> recentConsolidated, Trade lastConsolidated,
                       Trade lastIex) {

    public Inputs {
      recentConsolidated = recentConsolidated == null ? List.of() : List.copyOf(recentConsolidated);
    }
  }

  private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
  private static final Duration CONSISTENCY_WINDOW = Duration.ofSeconds(10);

  private final ExecQuoteRules rules;

  public ExecutableQuoteBuilder(ExecQuoteRules rules) {
    this.rules = rules;
  }

  public Optional<ExecQuote> build(Inputs in, Instant now, Instant notBefore, SessionType session) {
    if (in.halted() || session == null || session == SessionType.CLOSED) {
      return Optional.empty();
    }
    Instant floor = notBefore == null ? Instant.MIN : notBefore;
    boolean ext = session != SessionType.REGULAR;
    Duration maxConfirm = ext ? rules.extConfirmed() : rules.regularConfirmed();
    Duration maxAge = ext ? rules.extExchangeAge() : rules.regularExchangeAge();
    Duration printWindow = ext ? rules.consolidatedPrintExt() : rules.consolidatedPrintRegular();

    Quote q = in.quote();
    boolean quoteOk = q != null && q.twoSided() && in.confirmedAt() != null && !in.confirmedAt().isBefore(floor)
        && !in.confirmedAt().isAfter(now) && !age(now, in.confirmedAt()).minus(maxConfirm).isPositive()
        && q.ts() != null && !age(now, q.ts()).minus(maxAge).isPositive();
    BigDecimal last = in.lastConsolidated() != null ? in.lastConsolidated().price()
        : in.lastIex() != null ? in.lastIex().price() : null;

    Trade freshConsolidated = fresh(in.lastConsolidated(), now, floor, printWindow);
    if (quoteOk) {
      if (in.consolidatedQuote()) {
        DataQuality quality = q.quality() == DataQuality.SIMULATED ? DataQuality.SIMULATED
            : DataQuality.REALTIME_CONSOLIDATED;
        return Optional.of(new ExecQuote(in.symbol(), q.bid(), q.bidSize(), q.ask(), q.askSize(), last, q.ts(),
            in.confirmedAt(), quality, q.source(), false));
      }
      BigDecimal spread = q.ask().subtract(q.bid());
      BigDecimal mid = q.bid().add(q.ask()).divide(BigDecimal.TWO, 6, RoundingMode.HALF_EVEN);
      boolean wide = spread.multiply(TEN_THOUSAND)
          .compareTo(mid.multiply(BigDecimal.valueOf(rules.iexMaxSpreadBps(in.tier())))) > 0;
      boolean inconsistent = inconsistent(in.recentConsolidated(), q, spread, now);
      if (!wide && !inconsistent) {
        return Optional.of(clamp(in, q, last, now));
      }
      // Too wide or contradicted by the tape: only a fresh consolidated print can stand in (rank 3).
      return freshConsolidated == null ? Optional.empty()
          : Optional.of(synthetic(in, freshConsolidated, last, ext));
    }
    Trade print = freshConsolidated != null ? freshConsolidated : fresh(in.lastIex(), now, floor, printWindow);
    return print == null ? Optional.empty() : Optional.of(synthetic(in, print, last, ext));
  }

  private static Duration age(Instant now, Instant t) {
    return Duration.between(t, now);
  }

  /** An eligible print observed in {@code [floor, now]} and at most {@code window} old. */
  private static Trade fresh(Trade t, Instant now, Instant floor, Duration window) {
    if (t == null || !t.eligible() || t.observedAt() == null) {
      return null;
    }
    Instant obs = t.observedAt();
    if (obs.isBefore(floor) || obs.isAfter(now) || age(now, obs).compareTo(window) > 0) {
      return null;
    }
    return t;
  }

  /** A consolidated print of the last 10 s outside {@code [bid − spread, ask + spread]}. */
  private static boolean inconsistent(List<Trade> prints, Quote q, BigDecimal spread, Instant now) {
    BigDecimal lo = q.bid().subtract(spread);
    BigDecimal hi = q.ask().add(spread);
    for (Trade t : prints) {
      if (t.eligible() && t.observedAt() != null && age(now, t.observedAt()).compareTo(CONSISTENCY_WINDOW) <= 0
          && !t.observedAt().isAfter(now) && (t.price().compareTo(lo) < 0 || t.price().compareTo(hi) > 0)) {
        return true;
      }
    }
    return false;
  }

  /** Rank 2, clamped around a consolidated print at most 2 s old when one exists. */
  private ExecQuote clamp(Inputs in, Quote q, BigDecimal last, Instant now) {
    BigDecimal bid = q.bid();
    BigDecimal ask = q.ask();
    boolean clamped = false;
    Trade p = in.lastConsolidated();
    if (p != null && p.eligible() && p.observedAt() != null && !p.observedAt().isAfter(now)
        && age(now, p.observedAt()).compareTo(rules.clampPrintMaxAge()) <= 0) {
      BigDecimal px = p.price();
      BigDecimal h = px.multiply(BigDecimal.valueOf(rules.tierSpreadBps(in.tier())))
          .divide(BigDecimal.TWO.multiply(TEN_THOUSAND), 6, RoundingMode.HALF_EVEN).max(Prices.tick(px));
      BigDecimal width = h.multiply(BigDecimal.valueOf(rules.clampHalfSpreads()));
      BigDecimal lo = Rounding.TICK_DOWN.apply(px.subtract(width));
      BigDecimal hi = Rounding.TICK_UP.apply(px.add(width));
      BigDecimal nb = bid.max(lo).min(hi);
      BigDecimal na = ask.max(lo).min(hi);
      clamped = nb.compareTo(bid) != 0 || na.compareTo(ask) != 0;
      bid = nb;
      ask = na;
    }
    return new ExecQuote(in.symbol(), bid, q.bidSize(), ask, q.askSize(), last, q.ts(), in.confirmedAt(),
        DataQuality.REALTIME_SINGLE_VENUE, q.source(), clamped);
  }

  /** Rank 3: {@code half = max(tick, last × tierBps/10⁴) × (EXT ? 2 : 1)}, size 100. */
  private ExecQuote synthetic(Inputs in, Trade print, BigDecimal last, boolean ext) {
    BigDecimal px = print.price();
    BigDecimal half = px.multiply(BigDecimal.valueOf(rules.tierSpreadBps(in.tier())))
        .divide(TEN_THOUSAND, 6, RoundingMode.HALF_EVEN).max(Prices.tick(px));
    if (ext) {
      half = half.multiply(BigDecimal.TWO);
    }
    BigDecimal bid = Rounding.TICK_DOWN.apply(px.subtract(half));
    if (bid.signum() <= 0) {
      bid = Prices.SUBDOLLAR_TICK.setScale(Prices.SCALE);
    }
    BigDecimal ask = Rounding.TICK_UP.apply(px.add(half));
    return new ExecQuote(in.symbol(), bid, 100, ask, 100, last != null ? last : px, print.ts(), print.observedAt(),
        DataQuality.SYNTHETIC, print.source(), false);
  }
}
