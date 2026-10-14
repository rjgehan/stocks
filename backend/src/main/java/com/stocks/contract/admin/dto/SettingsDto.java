package com.stocks.contract.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * §21.1 {@code Settings} ({@code GET/PUT /admin/settings}); JSON keys are the {@code app_setting} keys (§23.4).
 * {@code tradeFeedDelay} is an ISO-8601 duration string ({@code "PT0S"} by default).
 */
public record SettingsDto(
    @JsonProperty("league.defaultStartingCash") BigDecimal defaultStartingCash,
    @JsonProperty("league.allowSelfReset") boolean allowSelfReset,
    @JsonProperty("league.seasonStart") LocalDate seasonStart,
    @JsonProperty("league.eligibility.minFills") int eligibilityMinFills,
    @JsonProperty("league.eligibility.minSymbols") int eligibilityMinSymbols,
    @JsonProperty("social.tradeFeedDelay") String tradeFeedDelay,
    @JsonProperty("social.showDepositsInFeed") boolean showDepositsInFeed,
    @JsonProperty("bots.requireRationale") boolean botsRequireRationale,
    @JsonProperty("league.humanKindExcludesApiTraders") boolean humanKindExcludesApiTraders) {}
