package com.stocks.contract.watchlist.dto;

import java.util.List;

/** §18.3 {@code Watchlist}; {@code symbols} in sort order. */
public record WatchlistDto(String id, String name, int sortOrder, List<String> symbols) {}
