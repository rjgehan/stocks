package com.stocks.contract.portfolio;

import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;

/**
 * Phase 2 feed reactions (§16.11, WS4): one reaction per player per visible feed item. Both calls answer the item's
 * reactions after the change and publish them on {@code /topic/feed}; an item the caller cannot see (not visible yet,
 * unknown, or of a player hidden from the league) is {@code NOT_FOUND}.
 */
public interface FeedReactionService {

  /** Sets (or replaces) the player's reaction; idempotent. */
  FeedReactionsDto react(long playerId, String itemId, Reaction reaction);

  /** Removes the player's reaction; idempotent (removing nothing is fine). */
  FeedReactionsDto unreact(long playerId, String itemId);
}
