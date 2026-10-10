package com.stocks.trading.config;

import com.stocks.common.domain.LiquidityTier;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code stocks.rules} (§23.1, WS3): the trading rules of §10–§12. WS5's {@code RulesService} publishes them through
 * {@code GET /rules}; WS2's {@code ExecutableQuoteBuilder} may read the spread, clamp and freshness values. Every
 * percentage is a {@link BigDecimal} (no floating point in trading code, §5.2 rule 4). Missing keys take the §23.1
 * defaults, so a test can bind an empty map.
 */
@ConfigurationProperties("stocks.rules")
public record RulesProperties(Integer gtcDays, Integer maxOpenOrders, BigDecimal maxOrderQty,
                              BigDecimal marketCollarPct, BigDecimal holdPctRegular, BigDecimal holdPctQueued,
                              BigDecimal fatFingerWarnPct, BigDecimal priceTooFarPct,
                              Map<LiquidityTier, Integer> tierSpreadBps, Map<LiquidityTier, Integer> iexMaxSpreadBps,
                              IexClamp iexClamp, Freshness freshness, Fractional fractional, Gfv gfv, Pdt pdt,
                              ExtHours extHours, Fill fill, CancelLatency cancelLatency) {

  public RulesProperties {
    gtcDays = gtcDays == null ? 60 : gtcDays;
    maxOpenOrders = maxOpenOrders == null ? 50 : maxOpenOrders;
    maxOrderQty = maxOrderQty == null ? new BigDecimal("200000") : maxOrderQty;
    marketCollarPct = marketCollarPct == null ? new BigDecimal("5") : marketCollarPct;
    holdPctRegular = holdPctRegular == null ? new BigDecimal("102") : holdPctRegular;
    holdPctQueued = holdPctQueued == null ? new BigDecimal("115") : holdPctQueued;
    fatFingerWarnPct = fatFingerWarnPct == null ? new BigDecimal("5") : fatFingerWarnPct;
    priceTooFarPct = priceTooFarPct == null ? new BigDecimal("50") : priceTooFarPct;
    tierSpreadBps = tiers(tierSpreadBps, 2, 10, 50);
    iexMaxSpreadBps = tiers(iexMaxSpreadBps, 10, 40, 150);
    iexClamp = iexClamp == null ? new IexClamp(null, null) : iexClamp;
    freshness = freshness == null ? new Freshness(null, null, null, null, null, null) : freshness;
    fractional = fractional == null ? new Fractional(null, null, null, null) : fractional;
    gfv = gfv == null ? new Gfv(null, null, null, null) : gfv;
    pdt = pdt == null ? new Pdt(null) : pdt;
    extHours = extHours == null ? new ExtHours(null) : extHours;
    fill = fill == null ? new Fill(null) : fill;
    cancelLatency = cancelLatency == null ? new CancelLatency(null, null, null) : cancelLatency;
  }

  /** The Phase 1 shape (no cancel latency: off). */
  public RulesProperties(Integer gtcDays, Integer maxOpenOrders, BigDecimal maxOrderQty, BigDecimal marketCollarPct,
                         BigDecimal holdPctRegular, BigDecimal holdPctQueued, BigDecimal fatFingerWarnPct,
                         BigDecimal priceTooFarPct, Map<LiquidityTier, Integer> tierSpreadBps,
                         Map<LiquidityTier, Integer> iexMaxSpreadBps, IexClamp iexClamp, Freshness freshness,
                         Fractional fractional, Gfv gfv, Pdt pdt, ExtHours extHours, Fill fill) {
    this(gtcDays, maxOpenOrders, maxOrderQty, marketCollarPct, holdPctRegular, holdPctQueued, fatFingerWarnPct,
        priceTooFarPct, tierSpreadBps, iexMaxSpreadBps, iexClamp, freshness, fractional, gfv, pdt, extHours, fill,
        null);
  }

  /** The defaults of §23.1. */
  public static RulesProperties defaults() {
    return new RulesProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }

  private static Map<LiquidityTier, Integer> tiers(Map<LiquidityTier, Integer> given, int mega, int mid, int small) {
    Map<LiquidityTier, Integer> out = new EnumMap<>(LiquidityTier.class);
    out.put(LiquidityTier.MEGA, mega);
    out.put(LiquidityTier.MID, mid);
    out.put(LiquidityTier.SMALL, small);
    if (given != null) {
      out.putAll(given);
    }
    return Map.copyOf(out);
  }

  /** §12.3 clamp of an IEX touch around a fresh consolidated print. */
  public record IexClamp(Duration printMaxAge, Integer halfSpreads) {
    public IexClamp {
      printMaxAge = printMaxAge == null ? Duration.ofSeconds(2) : printMaxAge;
      halfSpreads = halfSpreads == null ? 3 : halfSpreads;
    }
  }

  /** §12.3 freshness limits of an executable quote. */
  public record Freshness(Duration regularConfirmed, Duration extConfirmed, Duration regularExchangeAge,
                          Duration extExchangeAge, Duration consolidatedPrintRegular, Duration consolidatedPrintExt) {
    public Freshness {
      regularConfirmed = regularConfirmed == null ? Duration.ofSeconds(15) : regularConfirmed;
      extConfirmed = extConfirmed == null ? Duration.ofSeconds(120) : extConfirmed;
      regularExchangeAge = regularExchangeAge == null ? Duration.ofMinutes(5) : regularExchangeAge;
      extExchangeAge = extExchangeAge == null ? Duration.ofMinutes(15) : extExchangeAge;
      consolidatedPrintRegular = consolidatedPrintRegular == null ? Duration.ofSeconds(10) : consolidatedPrintRegular;
      consolidatedPrintExt = consolidatedPrintExt == null ? Duration.ofSeconds(60) : consolidatedPrintExt;
    }
  }

  /** D7 fractional and notional orders. */
  public record Fractional(BigDecimal minBuyNotional, BigDecimal minSellNotional, BigDecimal minQty,
                           Boolean allowLimit) {
    public Fractional {
      minBuyNotional = minBuyNotional == null ? new BigDecimal("1.00") : minBuyNotional;
      minSellNotional = minSellNotional == null ? new BigDecimal("0.01") : minSellNotional;
      minQty = minQty == null ? new BigDecimal("0.00001") : minQty;
      allowLimit = allowLimit != null && allowLimit;
    }
  }

  /** §10.4 good-faith violations. */
  public record Gfv(Boolean requiresAck, Integer settledCashOnlyAt, Integer liquidationOnlyAt,
                    Integer restrictionDays) {
    public Gfv {
      requiresAck = requiresAck == null || requiresAck;
      settledCashOnlyAt = settledCashOnlyAt == null ? 3 : settledCashOnlyAt;
      liquidationOnlyAt = liquidationOnlyAt == null ? 4 : liquidationOnlyAt;
      restrictionDays = restrictionDays == null ? 90 : restrictionDays;
    }
  }

  /** D6: PDT is not enforced (FINRA RN 26-10). */
  public record Pdt(Boolean enforced) {
    public Pdt {
      enforced = enforced != null && enforced;
    }
  }

  /** §12.5: evaluate EXT orders only while IEX is live (08:00–17:00 ET). */
  public record ExtHours(Boolean requireRealQuotes) {
    public ExtHours {
      requireRealQuotes = requireRealQuotes != null && requireRealQuotes;
    }
  }

  /**
   * §11.10 (Phase 2): a user cancel of a working order takes {@code base} + 0–{@code jitterMax} of seeded jitter, and
   * the order shows {@code PENDING_CANCEL} meanwhile. Off by default.
   */
  public record CancelLatency(Boolean enabled, Duration base, Duration jitterMax) {
    public CancelLatency {
      enabled = enabled != null && enabled;
      base = base == null ? Duration.ofMillis(150) : base;
      jitterMax = jitterMax == null ? Duration.ofMillis(100) : jitterMax;
      if (base.isNegative() || jitterMax.isNegative()) {
        throw new IllegalArgumentException("stocks.rules.cancel-latency base and jitter-max must be ≥ 0");
      }
    }
  }

  /** §12.5: a resting limit needs a trade-through print, not just a quote cross. */
  public record Fill(Boolean restingRequiresPrint) {
    public Fill {
      restingRequiresPrint = restingRequiresPrint != null && restingRequiresPrint;
    }
  }
}
