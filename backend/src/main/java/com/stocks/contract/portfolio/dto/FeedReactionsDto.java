package com.stocks.contract.portfolio.dto;

import com.stocks.common.domain.PlayerKind;
import java.util.List;

/**
 * §18.3 {@code FeedReactions} (§16.11, Phase 2): the reactions of one feed item ({@code PUT}/{@code DELETE
 * /feed/{itemId}/reaction}, MCP {@code react_to_feed_item}, and the {@code /topic/feed} update, told apart from a
 * {@code FeedItem} by {@code itemId}). Only reactions with at least one player, in {@link Reaction} order.
 */
public record FeedReactionsDto(String itemId, List<FeedReactionDto> reactions) {

  /** The fixed emoji set (§16.11), in display order. */
  public enum Reaction { ROCKET, FIRE, GEM, CLAP, LAUGH, SKULL }

  /** One emoji on an item: how many players chose it, and who (listed players, oldest first). */
  public record FeedReactionDto(Reaction reaction, int count, List<ReactorDto> players) {}

  /** A player who reacted (public, like {@code PlayerSummary}). */
  public record ReactorDto(String handle, String displayName, PlayerKind kind) {}
}
