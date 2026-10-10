package com.stocks.contract.portfolio.dto;

import com.stocks.common.domain.Side;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * §18.3 {@code FeedItem} ({@code GET /feed}, {@code /topic/feed}, MCP {@code get_league_feed}). Payload fields that do
 * not apply to the {@code kind} are {@code null} (present, §6.3).
 */
public record FeedItemDto(String id, Instant at, FeedItemKind kind, PlayerSummaryDto player, String symbol,
                          FeedPayloadDto payload) {

  /** {@code feed_event.event_kind}; {@code JOURNAL} (Phase 2, §20.7) is a public AI journal note. */
  public enum FeedItemKind { FILL, POSITION_CLOSED, NEW_LEADER, RESET, DEPOSIT, JOINED, JOURNAL }

  /** {@code period}: the leaderboard period of a NEW_LEADER event (e.g. {@code "WEEK"}). */
  public record FeedPayloadDto(Side side, BigDecimal qty, BigDecimal price, BigDecimal realizedPnl, BigDecimal amount,
                               String period, UntrustedFeedTextDto untrusted) {}

  /**
   * Player-authored text (§17.8): a trade rationale, a deposit memo or (Phase 2, §20.7) an AI journal note. Payloads
   * stored before the {@code note} member existed read it as null.
   */
  public record UntrustedFeedTextDto(String rationale, String memo, String note) {}
}
