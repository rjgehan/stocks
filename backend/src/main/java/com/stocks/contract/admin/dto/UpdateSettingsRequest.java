package com.stocks.contract.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/** {@code PUT /admin/settings} body: {@code Partial<Settings>} (§21.1); null = unchanged. */
public record UpdateSettingsRequest(
    @JsonProperty("league.defaultStartingCash") BigDecimal defaultStartingCash,
    @JsonProperty("league.allowSelfReset") Boolean allowSelfReset,
    @JsonProperty("league.seasonStart") LocalDate seasonStart,
    @JsonProperty("league.eligibility.minFills") Integer eligibilityMinFills,
    @JsonProperty("league.eligibility.minSymbols") Integer eligibilityMinSymbols,
    @JsonProperty("social.tradeFeedDelay") String tradeFeedDelay,
    @JsonProperty("social.showDepositsInFeed") Boolean showDepositsInFeed,
    @JsonProperty("bots.requireRationale") Boolean botsRequireRationale,
    @JsonProperty("league.humanKindExcludesApiTraders") Boolean humanKindExcludesApiTraders) {}
