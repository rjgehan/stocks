package com.stocks.contract.watchlist.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /watchlists/{id}/symbols} (§18.2): {@code {symbol}}. */
public record AddWatchlistSymbolRequest(@NotBlank String symbol) {}
