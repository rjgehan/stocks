package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.contract.portfolio.dto.RaceChartDto.RacePointDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * §18.3 {@code BenchmarkCurve} (Phase 2, §16.9 race chart and §16.10 P&amp;L analysis): a total-return index over a
 * window as returns {@code B(t) / B(a) − 1}, and its period {@code return} (null when B is unknown at either end).
 */
public record BenchmarkCurveDto(String symbol, @JsonProperty("return") BigDecimal ret, List<RacePointDto> points) {}
