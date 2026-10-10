package com.stocks.contract.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** {@code POST /watchlists} (§18.2): {@code {name, symbols?}}. */
public record CreateWatchlistRequest(@NotBlank String name, List<String> symbols) {}
