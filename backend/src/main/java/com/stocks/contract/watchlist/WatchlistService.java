package com.stocks.contract.watchlist;

/** Watchlist hooks other workstreams call (§5.3, WS4). */
public interface WatchlistService {

  /** "My List" with 5 popular tickers (signup, admin create). */
  void seedDefault(long playerId);

  /** NAME_CHANGE: runs inside the CALLER's (engine) transaction. */
  void renameSymbol(String oldSymbol, String newSymbol);
}
