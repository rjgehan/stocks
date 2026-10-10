package com.stocks.alerts;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 2 price alerts, {@code stocks.alerts} (§19.1, §23.1). Every key has a default, so a deployment without any of
 * them (production's unchanged {@code .env}) runs with these values.
 *
 * @param enabled            false stops the evaluation thread (alerts can still be edited; nothing fires)
 * @param maxActivePerPlayer ACTIVE + PAUSED alerts per player ({@code TOO_MANY_ALERTS} beyond it)
 * @param maxStoredPerPlayer every alert row of a player; the oldest TRIGGERED/EXPIRED ones make room
 * @param maxPriceAge        a print older than this is not a live price
 * @param rearmBand          repeating alerts re-arm this fraction (price) or these points (change) back past the
 *                           threshold
 * @param cooldown           minimum time between two firings of one repeating alert
 * @param evaluateEvery      the evaluation thread's period
 * @param retention          TRIGGERED and EXPIRED alerts are deleted this long after their last change
 */
@ConfigurationProperties("stocks.alerts")
public record AlertProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("50") int maxActivePerPlayer,
    @DefaultValue("500") int maxStoredPerPlayer,
    @DefaultValue("15m") Duration maxPriceAge,
    @DefaultValue("0.005") BigDecimal rearmBand,
    @DefaultValue("15m") Duration cooldown,
    @DefaultValue("250ms") Duration evaluateEvery,
    @DefaultValue("90d") Duration retention) {

  /** The defaults, for tests and components built without Spring. */
  public static AlertProperties defaults() {
    return new AlertProperties(true, 50, 500, Duration.ofMinutes(15), new BigDecimal("0.005"), Duration.ofMinutes(15),
        Duration.ofMillis(250), Duration.ofDays(90));
  }
}
