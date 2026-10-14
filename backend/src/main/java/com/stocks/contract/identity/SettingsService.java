package com.stocks.contract.identity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/** Runtime settings in {@code app_setting} with typed accessors, cached 5 s (§23.4, WS1). */
public interface SettingsService {

  /** {@code league.defaultStartingCash} (scale 2). */
  BigDecimal defaultStartingCash();

  /** {@code league.allowSelfReset}. */
  boolean allowSelfReset();

  /** {@code league.seasonStart} (ET date). */
  LocalDate seasonStart();

  /** {@code league.eligibility.minFills}. */
  int eligibilityMinFills();

  /** {@code league.eligibility.minSymbols}. */
  int eligibilityMinSymbols();

  /** {@code social.tradeFeedDelay}. */
  Duration tradeFeedDelay();

  /** {@code bots.requireRationale}. */
  boolean botsRequireRationale();

  /** {@code social.showDepositsInFeed}. */
  boolean showDepositsInFeed();

  /** {@code league.humanKindExcludesApiTraders}. */
  boolean humanKindExcludesApiTraders();

  /** Every setting as stored (key → string value). */
  Map<String, String> all();

  /**
   * Validates the key and the value's type, stores it and evicts the cache.
   *
   * @throws com.stocks.common.error.ApiException {@code SETTING_INVALID}
   */
  void put(String key, String value, long adminPlayerId);
}
