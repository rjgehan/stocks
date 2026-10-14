package com.stocks.portfolio;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.SettingsService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The §23.4 runtime settings the league reads, through WS1's {@link SettingsService}. While that is still the Day-0
 * stub (501 {@code NOT_IMPLEMENTED}) every accessor answers the §23.4 default, so the league works before settings
 * land; any other error propagates.
 */
@Component
public class LeagueSettings {

  /** §23.4 defaults. */
  public static final LocalDate DEFAULT_SEASON_START = LocalDate.of(2026, 10, 12);
  public static final int DEFAULT_MIN_FILLS = 5;
  public static final int DEFAULT_MIN_SYMBOLS = 2;
  public static final Duration DEFAULT_TRADE_FEED_DELAY = Duration.ZERO;

  private final SettingsService settings;

  public LeagueSettings(SettingsService settings) {
    this.settings = settings;
  }

  public LocalDate seasonStart() {
    return orDefault(settings::seasonStart, DEFAULT_SEASON_START);
  }

  public int minFills() {
    return orDefault(settings::eligibilityMinFills, DEFAULT_MIN_FILLS);
  }

  public int minSymbols() {
    return orDefault(settings::eligibilityMinSymbols, DEFAULT_MIN_SYMBOLS);
  }

  public Duration tradeFeedDelay() {
    return orDefault(settings::tradeFeedDelay, DEFAULT_TRADE_FEED_DELAY);
  }

  public boolean showDepositsInFeed() {
    return orDefault(settings::showDepositsInFeed, true);
  }

  public boolean humanKindExcludesApiTraders() {
    return orDefault(settings::humanKindExcludesApiTraders, false);
  }

  private static <T> T orDefault(Supplier<T> read, T fallback) {
    try {
      T v = read.get();
      return v == null ? fallback : v;
    } catch (ApiException e) {
      if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
        throw e;
      }
      return fallback;
    }
  }
}
