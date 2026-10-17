package com.stocks.identity;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * The runtime settings of §23.4 ({@code app_setting}): key, default and value rules. {@link #normalize} validates a
 * raw value and returns its canonical stored form, or throws 422 {@code SETTING_INVALID} naming the key.
 */
public enum SettingKey {

  DEFAULT_STARTING_CASH("league.defaultStartingCash", "500000.00") {
    @Override
    String canonical(String v) {
      BigDecimal d = new BigDecimal(v.trim());
      if (d.stripTrailingZeros().scale() > 2) {
        throw new IllegalArgumentException("at most 2 decimal places");
      }
      if (d.compareTo(BigDecimal.ONE) < 0) {
        throw new IllegalArgumentException("must be at least 1.00");
      }
      if (d.compareTo(MAX_CASH) > 0) {
        throw new IllegalArgumentException("must be at most " + MAX_CASH.toPlainString());
      }
      return d.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
  },
  ALLOW_SELF_RESET("league.allowSelfReset", "false") {
    @Override
    String canonical(String v) {
      return bool(v);
    }
  },
  SEASON_START("league.seasonStart", "2026-10-12") {
    @Override
    String canonical(String v) {
      try {
        return LocalDate.parse(v.trim()).toString();
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("a date such as 2026-10-12");
      }
    }
  },
  ELIGIBILITY_MIN_FILLS("league.eligibility.minFills", "5") {
    @Override
    String canonical(String v) {
      return nonNegativeInt(v);
    }
  },
  ELIGIBILITY_MIN_SYMBOLS("league.eligibility.minSymbols", "2") {
    @Override
    String canonical(String v) {
      return nonNegativeInt(v);
    }
  },
  TRADE_FEED_DELAY("social.tradeFeedDelay", "PT0S") {
    @Override
    String canonical(String v) {
      Duration d;
      try {
        d = Duration.parse(v.trim().toUpperCase(Locale.ROOT));
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("an ISO-8601 duration such as PT15M");
      }
      if (d.isNegative() || d.compareTo(Duration.ofDays(7)) > 0) {
        throw new IllegalArgumentException("between PT0S and P7D");
      }
      return d.toString();
    }
  },
  SHOW_DEPOSITS_IN_FEED("social.showDepositsInFeed", "true") {
    @Override
    String canonical(String v) {
      return bool(v);
    }
  },
  BOTS_REQUIRE_RATIONALE("bots.requireRationale", "true") {
    @Override
    String canonical(String v) {
      return bool(v);
    }
  },
  HUMAN_KIND_EXCLUDES_API_TRADERS("league.humanKindExcludesApiTraders", "false") {
    @Override
    String canonical(String v) {
      return bool(v);
    }
  },
  /** §29: crypto pairs trade 24/7, and every calendar day is a P&amp;L day, while this is on. */
  CRYPTO_ENABLED("crypto.enabled", "true") {
    @Override
    String canonical(String v) {
      return bool(v);
    }
  };

  /** Upper bound for any starting cash (fits NUMERIC(19,2) with room for every deposit). */
  public static final BigDecimal MAX_CASH = new BigDecimal("1000000000.00");

  private final String key;
  private final String defaultValue;

  SettingKey(String key, String defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }

  /** The {@code app_setting.setting_key} (also the JSON key of §21.1 {@code Settings}). */
  public String key() {
    return key;
  }

  /** The §23.4 default (the starting cash default is overridden by {@code STARTING_CASH_DEFAULT} at seed time). */
  public String defaultValue() {
    return defaultValue;
  }

  abstract String canonical(String value);

  /**
   * The canonical stored form of {@code value}.
   *
   * @throws ApiException 422 {@code SETTING_INVALID} with the key as the field
   */
  public String normalize(String value) {
    if (value == null || value.isBlank()) {
      throw invalid(this, "a value is required");
    }
    try {
      return canonical(value);
    } catch (IllegalArgumentException e) { // includes NumberFormatException
      throw invalid(this, e.getMessage() == null || e instanceof NumberFormatException ? "not a valid value"
          : e.getMessage());
    }
  }

  public static Optional<SettingKey> of(String key) {
    for (SettingKey k : values()) {
      if (k.key.equals(key)) {
        return Optional.of(k);
      }
    }
    return Optional.empty();
  }

  static ApiException invalid(SettingKey k, String message) {
    return ApiException.of(ErrorCode.SETTING_INVALID, "Invalid value for " + k.key + ": " + message + ".")
        .withField(k.key, ErrorCode.SETTING_INVALID, message);
  }

  private static String bool(String v) {
    String s = v.trim().toLowerCase(Locale.ROOT);
    if (!s.equals("true") && !s.equals("false")) {
      throw new IllegalArgumentException("true or false");
    }
    return s;
  }

  private static String nonNegativeInt(String v) {
    int n;
    try {
      n = Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("a whole number");
    }
    if (n < 0 || n > 1000) {
      throw new IllegalArgumentException("between 0 and 1000");
    }
    return Integer.toString(n);
  }
}
