package com.stocks.marketdata.config;

import com.stocks.common.domain.LiquidityTier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The executable-quote rules of §12.3, read from the {@code stocks.rules} keys that WS3 owns ({@code tier-spread-bps},
 * {@code iex-max-spread-bps}, {@code iex-clamp}, {@code freshness}). WS2 applies them in {@code ExecutableQuoteBuilder}
 * and reads them through a {@link Binder}, so this record is not a second owner of the prefix. Defaults = §23.1.
 */
public record ExecQuoteRules(Map<LiquidityTier, Integer> tierSpreadBps, Map<LiquidityTier, Integer> iexMaxSpreadBps,
                             Duration clampPrintMaxAge, int clampHalfSpreads, Duration regularConfirmed,
                             Duration extConfirmed, Duration regularExchangeAge, Duration extExchangeAge,
                             Duration consolidatedPrintRegular, Duration consolidatedPrintExt) {

  /** §23.1 defaults. */
  public static ExecQuoteRules defaults() {
    return new ExecQuoteRules(tiers(2, 10, 50), tiers(10, 40, 150), Duration.ofSeconds(2), 3, Duration.ofSeconds(15),
        Duration.ofSeconds(120), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(10),
        Duration.ofSeconds(60));
  }

  public static ExecQuoteRules from(Environment env) {
    Binder b = Binder.get(env);
    ExecQuoteRules d = defaults();
    return new ExecQuoteRules(
        tierMap(b, "stocks.rules.tier-spread-bps", d.tierSpreadBps()),
        tierMap(b, "stocks.rules.iex-max-spread-bps", d.iexMaxSpreadBps()),
        b.bind("stocks.rules.iex-clamp.print-max-age", Duration.class).orElse(d.clampPrintMaxAge()),
        b.bind("stocks.rules.iex-clamp.half-spreads", Integer.class).orElse(d.clampHalfSpreads()),
        b.bind("stocks.rules.freshness.regular-confirmed", Duration.class).orElse(d.regularConfirmed()),
        b.bind("stocks.rules.freshness.ext-confirmed", Duration.class).orElse(d.extConfirmed()),
        b.bind("stocks.rules.freshness.regular-exchange-age", Duration.class).orElse(d.regularExchangeAge()),
        b.bind("stocks.rules.freshness.ext-exchange-age", Duration.class).orElse(d.extExchangeAge()),
        b.bind("stocks.rules.freshness.consolidated-print-regular", Duration.class)
            .orElse(d.consolidatedPrintRegular()),
        b.bind("stocks.rules.freshness.consolidated-print-ext", Duration.class).orElse(d.consolidatedPrintExt()));
  }

  public int tierSpreadBps(LiquidityTier tier) {
    return tierSpreadBps.getOrDefault(tier == null ? LiquidityTier.SMALL : tier, 50);
  }

  public int iexMaxSpreadBps(LiquidityTier tier) {
    return iexMaxSpreadBps.getOrDefault(tier == null ? LiquidityTier.SMALL : tier, 150);
  }

  private static Map<LiquidityTier, Integer> tiers(int mega, int mid, int small) {
    Map<LiquidityTier, Integer> m = new EnumMap<>(LiquidityTier.class);
    m.put(LiquidityTier.MEGA, mega);
    m.put(LiquidityTier.MID, mid);
    m.put(LiquidityTier.SMALL, small);
    return Map.copyOf(m);
  }

  private static Map<LiquidityTier, Integer> tierMap(Binder b, String key, Map<LiquidityTier, Integer> fallback) {
    Map<LiquidityTier, Integer> m = new EnumMap<>(fallback);
    for (LiquidityTier t : LiquidityTier.values()) {
      b.bind(key + "." + t.name(), Integer.class).ifBound(v -> m.put(t, v));
    }
    return Map.copyOf(m);
  }
}
