package com.stocks.rules;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * A read-only view of the configuration the rulebook publishes (§18.3 {@code Rules}): {@code stocks.rules} and the
 * engine latency of {@code stocks.engine} (WS3's keys, §23.1), and {@code stocks.marketdata.profile} (WS2's). Bound
 * here with {@link Binder} rather than through WS3's {@code RulesProperties}, which does not exist on this branch;
 * the defaults mirror {@code application.yml}. Once WS3's properties land, {@code RulesService} may read them instead
 * (same keys, same values).
 */
public record RulesConfig(Rules rules, Engine engine, String marketDataProfile, String alpacaFeed) {

  public record Rules(@DefaultValue("60") int gtcDays,
                      @DefaultValue("50") int maxOpenOrders,
                      @DefaultValue("200000") BigDecimal maxOrderQty,
                      @DefaultValue("5") BigDecimal marketCollarPct,
                      @DefaultValue("102") BigDecimal holdPctRegular,
                      @DefaultValue("115") BigDecimal holdPctQueued,
                      @DefaultValue("5") BigDecimal fatFingerWarnPct,
                      @DefaultValue("50") BigDecimal priceTooFarPct,
                      @DefaultValue Fractional fractional,
                      @DefaultValue Gfv gfv,
                      @DefaultValue Pdt pdt,
                      @DefaultValue Freshness freshness,
                      @DefaultValue ExtHours extHours,
                      @DefaultValue Fill fill) {}

  public record Fractional(@DefaultValue("1.00") BigDecimal minBuyNotional,
                           @DefaultValue("0.01") BigDecimal minSellNotional,
                           @DefaultValue("0.00001") BigDecimal minQty,
                           @DefaultValue("false") boolean allowLimit) {}

  public record Gfv(@DefaultValue("true") boolean requiresAck,
                    @DefaultValue("3") int settledCashOnlyAt,
                    @DefaultValue("4") int liquidationOnlyAt,
                    @DefaultValue("90") int restrictionDays) {}

  public record Pdt(@DefaultValue("false") boolean enforced) {}

  public record Freshness(@DefaultValue("15s") Duration regularConfirmed,
                          @DefaultValue("120s") Duration extConfirmed,
                          @DefaultValue("5m") Duration regularExchangeAge,
                          @DefaultValue("15m") Duration extExchangeAge,
                          @DefaultValue("10s") Duration consolidatedPrintRegular,
                          @DefaultValue("60s") Duration consolidatedPrintExt) {}

  public record ExtHours(@DefaultValue("false") boolean requireRealQuotes) {}

  public record Fill(@DefaultValue("false") boolean restingRequiresPrint) {}

  public record Engine(@DefaultValue("250ms") Duration latencyBase,
                       @DefaultValue("100ms") Duration latencyJitterMin,
                       @DefaultValue("500ms") Duration latencyJitterMax) {}

  /** Binds the current values from {@code env} (missing keys take the defaults above). */
  public static RulesConfig from(Environment env) {
    Binder binder = Binder.get(env);
    Rules rules = binder.bindOrCreate("stocks.rules", Bindable.of(Rules.class));
    Engine engine = binder.bindOrCreate("stocks.engine", Bindable.of(Engine.class));
    String profile = binder.bind("stocks.marketdata.profile", String.class).orElse("simulated");
    String feed = binder.bind("stocks.marketdata.alpaca.feed", String.class).orElse("iex");
    return new RulesConfig(rules, engine, profile, feed);
  }
}
