-- =====================================================================================================
-- V1_4_01 (WS4, §8 "Migrations after V1"): read paths of valuation, the leaderboard and watchlists.
-- Portable (PostgreSQL 18.6 and H2 2.4, MODE=PostgreSQL). Squashed into V1 before the first deploy (§8).
-- =====================================================================================================

-- Leaderboard anchors load one EOD day for every account (EodSnapshotRepository.byDate).
CREATE INDEX ix_eqsnap_date ON equity_snapshot(trade_date);

-- Valuation fallback: the latest fill of a symbol (MarkResolver, PortfolioRepository.lastFillPrice).
CREATE INDEX ix_exec_symbol_time ON execution(symbol, executed_at);

-- NAME_CHANGE renames every watchlist row of a symbol (WatchlistService.renameSymbol).
CREATE INDEX ix_watchlist_item_symbol ON watchlist_item(symbol);

-- A player's lists in order (GET /watchlists).
CREATE INDEX ix_watchlist_player ON watchlist(player_id, sort_order);
