package com.stocks.contract.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §30 {@code GET /options/{underlying}/chain?expiration=…}: one expiration's calls and puts by ascending strike.
 * {@code feed}: {@code indicative} (Alpaca's free derived quotes, not OPRA), {@code opra} or {@code simulated}.
 * {@code enabled}: buying options is allowed ({@code options.enabled}); sells to close always are.
 */
public record OptionChainDto(String underlying, LocalDate expiration, boolean enabled, String feed, int multiplier,
                             BigDecimal underlyingPrice, Instant asOf, List<OptionContractDto> calls,
                             List<OptionContractDto> puts) {}
