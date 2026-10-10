package com.stocks.contract.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** {@code PUT /watchlists/{id}} (§18.2): {@code {name, symbols}}; array order = sort order. */
public record UpdateWatchlistRequest(@NotBlank String name, @NotNull List<String> symbols) {}
