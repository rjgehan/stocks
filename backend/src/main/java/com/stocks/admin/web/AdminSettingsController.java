package com.stocks.admin.web;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.contract.admin.dto.SettingsDto;
import com.stocks.contract.admin.dto.UpdateSettingsRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.SettingKey;
import com.stocks.identity.SettingsServiceImpl;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.1 league settings ({@code app_setting}, §23.4): starting cash for new players and resets, self-reset, season
 * start, leaderboard eligibility, feed delay, deposits in the feed, bot rationales.
 * {@code PUT} takes {@code Partial<Settings>} (all values validated, then written in one transaction);
 * {@code PATCH} is the same operation under its conventional verb.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminSettingsController {

  private final SettingsServiceImpl settings;
  private final AuditService audit;

  public AdminSettingsController(SettingsServiceImpl settings, AuditService audit) {
    this.settings = settings;
    this.audit = audit;
  }

  @GetMapping
  @Operation(summary = "League settings")
  public SettingsDto get() {
    return dto();
  }

  @PutMapping
  @RecentAuth
  @Operation(summary = "Change league settings",
      description = "Recent password check. Partial: only the keys present change. league.defaultStartingCash is a "
          + "decimal string >= 1.00 (applies to new players and resets, never to existing accounts); "
          + "social.tradeFeedDelay an ISO-8601 duration (PT0S..P7D; default PT0S. In public mode it holds back only "
          + "the feed, the trade list and journal notes; portfolios, orders, activity and P&L are always live); "
          + "crypto.enabled turns 24/7 crypto trading and weekend P&L days on or off (off: crypto buys are refused, "
          + "sells still work); options.enabled turns buying long calls and puts on or off (off: option buys are "
          + "refused, sells to close still work). "
          + "Errors: SETTING_INVALID.")
  public SettingsDto put(@RequestBody UpdateSettingsRequest body, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    Map<SettingKey, String> values = new LinkedHashMap<>();
    put(values, SettingKey.DEFAULT_STARTING_CASH, body.defaultStartingCash());
    put(values, SettingKey.ALLOW_SELF_RESET, body.allowSelfReset());
    put(values, SettingKey.SEASON_START, body.seasonStart());
    put(values, SettingKey.ELIGIBILITY_MIN_FILLS, body.eligibilityMinFills());
    put(values, SettingKey.ELIGIBILITY_MIN_SYMBOLS, body.eligibilityMinSymbols());
    put(values, SettingKey.TRADE_FEED_DELAY, body.tradeFeedDelay());
    put(values, SettingKey.SHOW_DEPOSITS_IN_FEED, body.showDepositsInFeed());
    put(values, SettingKey.BOTS_REQUIRE_RATIONALE, body.botsRequireRationale());
    put(values, SettingKey.HUMAN_KIND_EXCLUDES_API_TRADERS, body.humanKindExcludesApiTraders());
    put(values, SettingKey.CRYPTO_ENABLED, body.cryptoEnabled());
    put(values, SettingKey.OPTIONS_ENABLED, body.optionsEnabled());
    if (!values.isEmpty()) {
      Map<SettingKey, String> written = settings.putAll(values, admin.playerId());
      Map<String, Object> detail = new LinkedHashMap<>();
      written.forEach((k, v) -> detail.put(k.key(), v));
      audit.record(AuditEntry.of(AuditCategory.ADMIN, "SETTINGS_UPDATE", AuditOutcome.OK).withActor(admin)
          .withMeta(RequestMetas.of(request, admin)).withTarget("settings", null).withDetail("changes", detail));
    }
    return dto();
  }

  @PatchMapping
  @RecentAuth
  @Operation(summary = "Change league settings (same as PUT)", description = "Partial update; see PUT.")
  public SettingsDto patch(@RequestBody UpdateSettingsRequest body, HttpServletRequest request) {
    return put(body, request);
  }

  private static void put(Map<SettingKey, String> values, SettingKey key, Object value) {
    if (value == null) {
      return;
    }
    String s = value instanceof BigDecimal d ? d.toPlainString()
        : value instanceof LocalDate date ? date.toString()
        : value.toString();
    values.put(key, s);
  }

  private SettingsDto dto() {
    return new SettingsDto(settings.defaultStartingCash(), settings.allowSelfReset(), settings.seasonStart(),
        settings.eligibilityMinFills(), settings.eligibilityMinSymbols(), settings.tradeFeedDelay().toString(),
        settings.showDepositsInFeed(), settings.botsRequireRationale(), settings.humanKindExcludesApiTraders(),
        settings.cryptoEnabled(), settings.optionsEnabled());
  }
}
