package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.TransferType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** §18.3 {@code PortfolioHistory} ({@code GET /account/history}); {@code range} is the wire value ({@code "1D"}). */
public record PortfolioHistoryDto(HistoryRange range, List<HistoryPointDto> points, List<HistoryFlowDto> flows,
                                  HistorySummaryDto summary) {

  public record HistoryPointDto(Instant t, BigDecimal equity, BigDecimal netContributions, BigDecimal pnl,
                                BigDecimal unitValue, BigDecimal benchmarkIndex) {}

  public record HistoryFlowDto(Instant t, TransferType type, BigDecimal amount, String memo) {}

  public record HistorySummaryDto(@JsonProperty("return") BigDecimal ret, BigDecimal pnl,
                                  BigDecimal excessVsBenchmark, BigDecimal maxDrawdown) {}
}
