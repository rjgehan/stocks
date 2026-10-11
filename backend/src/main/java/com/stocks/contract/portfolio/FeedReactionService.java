package com.stocks.contract.portfolio;

import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;

/**
 * Phase 2 feed reactions (§16.11, WS4): one reaction per player per visible feed item. Both calls answer the item's
 * reactions after the change and publish them on {@code /topic/feed}; an item the caller cannot see (not visible yet,
 * unknown, or of a player hidden from the league) is {@code NOT_FOUND}. Each player may change reactions at most
 * {@link #MAX_CHANGES_PER_MINUTE} times a minute ({@code RATE_LIMITED} with {@code retryAfterS} beyond).
 */
public interface FeedReactionService {

  /** Reaction changes (sets and removals) per player and minute (§16.11). */
  int MAX_CHANGES_PER_MINUTE = 30;

  /**
   * The STOMP native header that marks a {@code /topic/feed} message as a reactions update (value
   * {@link #REACTIONS_KIND}), so the SSE tap sends it as the non-resumable {@code feedReactions} event instead of
   * buffering it like a feed item (§19.3).
   */
  String STOMP_KIND_HEADER = "x-stocks-kind";
  String REACTIONS_KIND = "reactions";

  /** Sets (or replaces) the player's reaction; idempotent. */
  FeedReactionsDto react(long playerId, String itemId, Reaction reaction);

  /** Removes the player's reaction; idempotent (removing nothing is fine). */
  FeedReactionsDto unreact(long playerId, String itemId);
}
