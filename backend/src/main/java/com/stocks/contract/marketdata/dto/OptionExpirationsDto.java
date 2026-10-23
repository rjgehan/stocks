package com.stocks.contract.marketdata.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * §30 {@code GET /options/{underlying}/expirations}: the listed expirations, nearest first. {@code feed} and
 * {@code enabled} as in {@link OptionChainDto}.
 */
public record OptionExpirationsDto(String underlying, boolean enabled, String feed, int multiplier,
                                   List<LocalDate> expirations) {}
