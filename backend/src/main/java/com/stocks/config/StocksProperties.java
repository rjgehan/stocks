package com.stocks.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WS1's slice of the {@code stocks.*} configuration (§23.1, §27.3 rule 3): {@code public-url}, {@code security},
 * {@code ratelimit}, {@code bootstrap}, {@code jobs} and {@code single-instance}. The other prefixes are bound by
 * their owners ({@code stocks.marketdata}: WS2 {@code MarketDataProperties}; {@code stocks.engine},
 * {@code stocks.rules}: WS3; {@code stocks.performance}: WS4), so nobody edits another workstream's record.
 * Defaults mirror application.yml so a missing key never yields a zero.
 */
@ConfigurationProperties("stocks")
public record StocksProperties(
    /* APP_PUBLIC_URL: invite links, WebSocket origin allowlist, MCP Origin check. Never derived from Host. */
    @DefaultValue("http://localhost:5173") String publicUrl,
    @DefaultValue Jobs jobs,
    @DefaultValue SingleInstance singleInstance,
    @DefaultValue Bootstrap bootstrap,
    @DefaultValue Security security,
    @DefaultValue RateLimit ratelimit) {

  /**
   * {@code stocks.jobs}: every ScheduledJob and PeriodicTask (§25) and the boot-time catch-up; not the engine, market
   * data or STOMP. {@code catchUpWindow}: how far back {@code CatchUpRunner} looks (§25: 7 days).
   */
  public record Jobs(@DefaultValue("true") boolean enabled, @DefaultValue("7d") Duration catchUpWindow) {}

  /** {@code stocks.single-instance}: the advisory-lock / H2-lease guard (§12.9); false in the test profile. */
  public record SingleInstance(@DefaultValue("true") boolean enabled) {}

  /** {@code stocks.bootstrap}: first-boot admin and the dev demo seed (§23.2). */
  public record Bootstrap(
      String adminHandle,
      String adminPassword,
      /* STARTING_CASH_DEFAULT: seeds app_setting league.defaultStartingCash once; the admin UI wins afterwards. */
      @DefaultValue("500.00") BigDecimal defaultStartingCash,
      @DefaultValue("false") boolean seedDemo) {

    public boolean hasAdminCredentials() {
      return adminHandle != null && !adminHandle.isBlank() && adminPassword != null && !adminPassword.isBlank();
    }
  }

  /** {@code stocks.security} (§17). */
  public record Security(
      /* API_KEY_PEPPER: 32+ random bytes, base64; prod fails fast when shorter (§23.2). The HMAC key is the UTF-8
         bytes of this string (§17.2). Blank = API keys can be neither issued nor verified. */
      String apiKeyPepper,
      @DefaultValue("10") int loginMaxFailures,
      @DefaultValue("15m") Duration loginLockout,
      @DefaultValue("15m") Duration recentAuth,
      /* Extra STOMP origins besides public-url; dev adds http://localhost:5173. */
      @DefaultValue List<String> extraWsOrigins,
      /* bcrypt cost for new password hashes (§17.3: 12). The test profile lowers it to keep suites fast;
         existing hashes verify at whatever cost they were made with. */
      @DefaultValue("12") int passwordHashCost,
      /* §17.2: API-key rows are cached this long (revocation evicts at once). 0 = no cache (the test profile, whose
         fixtures change rows behind the services' back). */
      @DefaultValue("60s") Duration apiKeyCacheTtl,
      /* §5.3 PlayerQueries.status: cached this long; AdminPlayerService evicts synchronously on every change. */
      @DefaultValue("60s") Duration playerStatusCacheTtl) {

    /** True when an API-key pepper is configured. */
    public boolean hasApiKeyPepper() {
      return apiKeyPepper != null && !apiKeyPepper.isBlank();
    }
  }

  /**
   * {@code stocks.ratelimit} (§17.5): per player, shared across all of a player's keys and channels; before
   * authentication, per client IP. {@code enabled=false} turns the read and per-IP buckets off (the order-write
   * bucket that {@code OrderGate} charges stays on).
   */
  public record RateLimit(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("120") int keyReadsPerMin,
      @DefaultValue("30") int keyReadsBurst,
      @DefaultValue("600") int webReadsPerMin,
      @DefaultValue("30") int heavyReadsPerMinKey,
      @DefaultValue("120") int heavyReadsPerMinWeb,
      @DefaultValue("20") int writesPerMin,
      @DefaultValue("300") int writesPerDay,
      @DefaultValue("10") int loginPerMinIp,
      @DefaultValue("5") int signupPerHourIp,
      @DefaultValue("300") int anonPerMinIp) {}
}
