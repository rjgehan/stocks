package com.stocks.trading.config;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.SettingsService;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * The runtime settings trading reads (§23.4), through WS1's {@link SettingsService}. While that contract is still a
 * Day-0 stub (501), the §23.4 defaults apply, so trading works before settings land.
 */
@Component
public class TradingSettings {

  public static final BigDecimal DEFAULT_STARTING_CASH = new BigDecimal("500000.00");

  private final SettingsService settings;

  public TradingSettings(SettingsService settings) {
    this.settings = settings;
  }

  /** {@code league.defaultStartingCash}, default 500000.00. */
  public BigDecimal defaultStartingCash() {
    try {
      return settings.defaultStartingCash();
    } catch (ApiException e) {
      return fallback(e, DEFAULT_STARTING_CASH);
    }
  }

  /** {@code bots.requireRationale}, default true. */
  public boolean botsRequireRationale() {
    try {
      return settings.botsRequireRationale();
    } catch (ApiException e) {
      return fallback(e, Boolean.TRUE);
    }
  }

  /** {@code crypto.enabled} (§29), default true. */
  public boolean cryptoEnabled() {
    try {
      return settings.cryptoEnabled();
    } catch (ApiException e) {
      return fallback(e, Boolean.TRUE);
    }
  }

  /** {@code league.allowSelfReset}, default false. */
  public boolean allowSelfReset() {
    try {
      return settings.allowSelfReset();
    } catch (ApiException e) {
      return fallback(e, Boolean.FALSE);
    }
  }

  private static <T> T fallback(ApiException e, T value) {
    if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
      throw e;
    }
    return value;
  }
}
