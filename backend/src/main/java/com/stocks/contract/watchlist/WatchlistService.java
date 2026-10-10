package com.stocks.contract.watchlist;

import com.stocks.contract.watchlist.dto.WatchlistDto;
import java.util.List;

/**
 * Watchlists (§5.3, §18.2, WS4). REST ({@code /watchlists/**}) and MCP ({@code get_watchlists},
 * {@code update_watchlist}, {@code delete_watchlist}) call the same methods, so a list never has two shapes.
 * {@code watchlistId} is the public id ({@code wlt_…}); a list that is not the player's is {@code NOT_FOUND}.
 * Symbols are trimmed and upper-cased; an unknown symbol is {@code SYMBOL_NOT_FOUND}; names are 1–40 characters and
 * unique per player ({@code VALIDATION_FAILED}).
 */
public interface WatchlistService {

  /** "My List" with 5 popular tickers (signup, admin create). No-op if the player already has a list. */
  void seedDefault(long playerId);

  /** NAME_CHANGE: runs inside the CALLER's (engine) transaction. */
  void renameSymbol(String oldSymbol, String newSymbol);

  /** The player's lists in sort order, each with its symbols in sort order. */
  List<WatchlistDto> list(long playerId);

  /** {@code POST /watchlists}: appended after the player's other lists; {@code symbols} may be null. */
  WatchlistDto create(long playerId, String name, List<String> symbols);

  /** {@code PUT /watchlists/{id}}: renames the list and replaces its symbols (array order = sort order). */
  WatchlistDto replace(long playerId, String watchlistId, String name, List<String> symbols);

  /** {@code DELETE /watchlists/{id}}. */
  void delete(long playerId, String watchlistId);

  /** {@code POST /watchlists/{id}/symbols}: appends the symbol (idempotent if already present). */
  WatchlistDto addSymbol(long playerId, String watchlistId, String symbol);

  /** {@code DELETE /watchlists/{id}/symbols/{symbol}} (idempotent if absent). */
  WatchlistDto removeSymbol(long playerId, String watchlistId, String symbol);

  /** MCP {@code update_watchlist}: by name, creating the list if missing; adds, then removes. */
  WatchlistDto upsertByName(long playerId, String name, List<String> add, List<String> remove);

  /** MCP {@code delete_watchlist}: false if no list has that name. */
  boolean deleteByName(long playerId, String name);
}
