package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * §18.3 {@code PlayerProfile}. {@code equity} null when {@code holdingsVisibility=HIDDEN}; {@code holdings} are
 * previous-EOD weights ({@code qty} only with {@code EOD_FULL}).
 */
public record PlayerProfileDto(PlayerSummaryDto player, String ownerHandle, int runNo, ProfileStatsDto stats,
                               BigDecimal equity, BigDecimal netContributions, DepositsSummaryDto deposits, int fills,
                               BigDecimal winRate, BigDecimal moneyWeightedReturnAll, LocalDate holdingsAsOf,
                               List<ProfileHoldingDto> holdings, List<PublicTradeDto> recentTrades) {

  /** {@code stats: Record<"TODAY"|"WEEK"|"MONTH"|"YTD"|"ALL", {return, pnl, rank}>}. */
  public record ProfileStatsDto(@JsonProperty("TODAY") ProfileStatDto today,
                                @JsonProperty("WEEK") ProfileStatDto week,
                                @JsonProperty("MONTH") ProfileStatDto month,
                                @JsonProperty("YTD") ProfileStatDto ytd,
                                @JsonProperty("ALL") ProfileStatDto all) {}

  public record ProfileStatDto(@JsonProperty("return") BigDecimal ret, BigDecimal pnl, Integer rank) {}

  public record DepositsSummaryDto(int count, BigDecimal total) {}

  public record ProfileHoldingDto(String symbol, BigDecimal weight, BigDecimal qty) {}
}
