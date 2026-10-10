package com.stocks.contract.watchlist;

import com.stocks.common.error.ApiException;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import java.util.List;

/**
 * Watchlist hooks other workstreams call (§5.3, WS4). The three default methods back the MCP watchlist tools
 * (§20.3 {@code get_watchlists}, {@code update_watchlist}, {@code delete_watchlist}; added by WS5 with the names and
 * signatures of WS4's {@code WatchlistService}, which declares them abstract and supersedes this file at merge); WS4's
 * implementation overrides them with the same rules as {@code /watchlists} (symbols validated, upper-cased,
 * de-duplicated, order kept).
 */
public interface WatchlistService {

  /** "My List" with 5 popular tickers (signup, admin create). */
  void seedDefault(long playerId);

  /** NAME_CHANGE: runs inside the CALLER's (engine) transaction. */
  void renameSymbol(String oldSymbol, String newSymbol);

  /** The player's watchlists in sort order ({@code GET /watchlists}). */
  default List<WatchlistDto> list(long playerId) {
    throw ApiException.notImplemented("WatchlistService.list (WS4)");
  }

  /**
   * MCP {@code update_watchlist}: creates the list {@code name} if it does not exist, then appends {@code add}
   * (skipping symbols already present) and removes {@code remove}; idempotent. {@code SYMBOL_NOT_FOUND} for an unknown
   * symbol, {@code VALIDATION_FAILED} for a bad name.
   */
  default WatchlistDto upsertByName(long playerId, String name, List<String> add, List<String> remove) {
    throw ApiException.notImplemented("WatchlistService.upsertByName (WS4)");
  }

  /** MCP {@code delete_watchlist}: true if the list existed and was deleted. */
  default boolean deleteByName(long playerId, String name) {
    throw ApiException.notImplemented("WatchlistService.deleteByName (WS4)");
  }
}
