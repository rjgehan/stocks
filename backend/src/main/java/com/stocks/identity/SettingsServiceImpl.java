package com.stocks.identity;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.SettingsService;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SettingsService} over {@code app_setting} (§23.4): typed accessors over a snapshot cached for 5 s, admin
 * writes validated per {@link SettingKey} (422 {@code SETTING_INVALID}). Missing rows are seeded at boot with the
 * §23.4 defaults; {@code league.defaultStartingCash} is seeded from {@code STARTING_CASH_DEFAULT}
 * ({@code stocks.bootstrap.default-starting-cash}) <b>once</b>, after which the admin UI wins. A value that is
 * missing or unreadable at read time falls back to its default, so a bad row never breaks the league.
 */
@Service
public class SettingsServiceImpl implements SettingsService, SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(SettingsServiceImpl.class);
  static final long CACHE_NANOS = Duration.ofSeconds(5).toNanos();

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final StocksProperties properties;
  private final TransactionTemplate tx;

  private volatile Map<String, String> snapshot;
  private volatile long snapshotAt;

  public SettingsServiceImpl(JdbcClient jdbc, TimeSource time, StocksProperties properties, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.time = time;
    this.properties = properties;
    this.tx = tx;
  }

  @Override
  public void afterSingletonsInstantiated() {
    seedMissing();
  }

  /** Inserts every missing key with its default (idempotent; existing values are never touched). */
  public int seedMissing() {
    int seeded = 0;
    for (SettingKey k : SettingKey.values()) {
      String value = k == SettingKey.DEFAULT_STARTING_CASH ? seedStartingCash() : k.defaultValue();
      seeded += jdbc.sql("""
              MERGE INTO app_setting t
              USING (VALUES (CAST(? AS VARCHAR(80)), CAST(? AS VARCHAR(2000)),
                             CAST(? AS TIMESTAMP WITH TIME ZONE))) AS v(k, val, at)
                 ON t.setting_key = v.k
              WHEN NOT MATCHED THEN INSERT (setting_key, setting_value, updated_by, updated_at)
                                    VALUES (v.k, v.val, NULL, v.at)""")
          .params(k.key(), value, Jdbc.ts(time.now()))
          .update();
    }
    if (seeded > 0) {
      log.info("Seeded {} runtime setting(s) with their defaults", seeded);
    }
    invalidate();
    return seeded;
  }

  private String seedStartingCash() {
    BigDecimal configured = properties.bootstrap().defaultStartingCash();
    try {
      return SettingKey.DEFAULT_STARTING_CASH.normalize(configured == null ? null : configured.toPlainString());
    } catch (ApiException e) {
      log.warn("STARTING_CASH_DEFAULT={} is invalid ({}); seeding {}", configured, e.detail(),
          SettingKey.DEFAULT_STARTING_CASH.defaultValue());
      return SettingKey.DEFAULT_STARTING_CASH.defaultValue();
    }
  }

  // ------------------------------------------------------------------------------------------- typed accessors

  @Override
  public BigDecimal defaultStartingCash() {
    return new BigDecimal(value(SettingKey.DEFAULT_STARTING_CASH));
  }

  @Override
  public boolean allowSelfReset() {
    return Boolean.parseBoolean(value(SettingKey.ALLOW_SELF_RESET));
  }

  @Override
  public LocalDate seasonStart() {
    return LocalDate.parse(value(SettingKey.SEASON_START));
  }

  @Override
  public int eligibilityMinFills() {
    return Integer.parseInt(value(SettingKey.ELIGIBILITY_MIN_FILLS));
  }

  @Override
  public int eligibilityMinSymbols() {
    return Integer.parseInt(value(SettingKey.ELIGIBILITY_MIN_SYMBOLS));
  }

  @Override
  public Duration tradeFeedDelay() {
    return Duration.parse(value(SettingKey.TRADE_FEED_DELAY));
  }

  @Override
  public boolean botsRequireRationale() {
    return Boolean.parseBoolean(value(SettingKey.BOTS_REQUIRE_RATIONALE));
  }

  @Override
  public boolean showDepositsInFeed() {
    return Boolean.parseBoolean(value(SettingKey.SHOW_DEPOSITS_IN_FEED));
  }

  @Override
  public boolean humanKindExcludesApiTraders() {
    return Boolean.parseBoolean(value(SettingKey.HUMAN_KIND_EXCLUDES_API_TRADERS));
  }

  @Override
  public boolean cryptoEnabled() {
    return Boolean.parseBoolean(value(SettingKey.CRYPTO_ENABLED));
  }

  @Override
  public boolean optionsEnabled() {
    return Boolean.parseBoolean(value(SettingKey.OPTIONS_ENABLED));
  }

  /** Every §23.4 key with its effective value, in §23.4 order. */
  @Override
  public Map<String, String> all() {
    Map<String, String> out = new LinkedHashMap<>();
    for (SettingKey k : SettingKey.values()) {
      out.put(k.key(), value(k));
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Validates and stores one setting.
   *
   * @throws ApiException 422 {@code SETTING_INVALID} for an unknown key or a bad value
   */
  @Override
  public void put(String key, String value, long adminPlayerId) {
    SettingKey k = SettingKey.of(key).orElseThrow(() -> ApiException.of(ErrorCode.SETTING_INVALID,
        "Unknown setting '" + key + "'.").withField(String.valueOf(key), ErrorCode.SETTING_INVALID, "Unknown key"));
    putAll(Map.of(k, value), adminPlayerId);
  }

  /**
   * Validates every value first, then writes them all in one transaction (all or nothing).
   *
   * @return the canonical values written
   */
  public Map<SettingKey, String> putAll(Map<SettingKey, String> values, long adminPlayerId) {
    Map<SettingKey, String> canonical = new LinkedHashMap<>();
    for (SettingKey k : SettingKey.values()) {
      if (values.containsKey(k)) {
        canonical.put(k, k.normalize(values.get(k)));
      }
    }
    tx.executeWithoutResult(status -> canonical.forEach((k, v) -> upsert(k, v, adminPlayerId)));
    invalidate();
    return canonical;
  }

  private void upsert(SettingKey k, String value, Long updatedBy) {
    jdbc.sql("""
            MERGE INTO app_setting t
            USING (VALUES (CAST(? AS VARCHAR(80)), CAST(? AS VARCHAR(2000)), CAST(? AS BIGINT),
                           CAST(? AS TIMESTAMP WITH TIME ZONE))) AS v(k, val, by_id, at)
               ON t.setting_key = v.k
            WHEN MATCHED THEN UPDATE SET setting_value = v.val, updated_by = v.by_id, updated_at = v.at
            WHEN NOT MATCHED THEN INSERT (setting_key, setting_value, updated_by, updated_at)
                                  VALUES (v.k, v.val, v.by_id, v.at)""")
        .params(k.key(), value, new SqlParameterValue(Types.BIGINT, updatedBy), Jdbc.ts(time.now()))
        .update();
  }

  /** Drops the cached snapshot (the next read reloads). */
  public void invalidate() {
    snapshot = null;
  }

  private String value(SettingKey k) {
    String stored = load().get(k.key());
    if (stored == null) {
      return k == SettingKey.DEFAULT_STARTING_CASH ? seedStartingCash() : k.defaultValue();
    }
    try {
      return k.normalize(stored);
    } catch (ApiException e) {
      log.warn("app_setting {}='{}' is invalid ({}); using the default", k.key(), stored, e.detail());
      return k.defaultValue();
    }
  }

  private Map<String, String> load() {
    Map<String, String> s = snapshot;
    long now = System.nanoTime();
    if (s != null && now - snapshotAt < CACHE_NANOS) {
      return s;
    }
    Map<String, String> fresh = new LinkedHashMap<>();
    jdbc.sql("SELECT setting_key, setting_value FROM app_setting")
        .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
        .list()
        .forEach(e -> fresh.put(e.getKey(), e.getValue()));
    snapshot = Collections.unmodifiableMap(fresh);
    snapshotAt = now;
    return snapshot;
  }
}
