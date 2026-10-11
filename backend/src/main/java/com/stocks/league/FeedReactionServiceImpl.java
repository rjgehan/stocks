package com.stocks.league;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.portfolio.FeedReactionService;
import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import com.stocks.league.FeedReactionRepository.ItemRef;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * {@link FeedReactionService} (§16.11, Phase 2): one reaction per player per visible feed item, written on the request
 * thread with one auto-committed statement (social data, not money: no engine, no audit). Each change answers the item's reactions and
 * pushes them on {@code /topic/feed} after the write. Humans react from the feed; bots through MCP
 * {@code react_to_feed_item}, within their normal read limits. Every change (a set or a removal) also counts against
 * {@value FeedReactionService#MAX_CHANGES_PER_MINUTE} per player and minute (a fixed window on {@link TimeSource}
 * time, in memory), so one player's toggles cannot flood every client's feed: beyond it {@code RATE_LIMITED} with
 * {@code retryAfterS}.
 */
@Service
public class FeedReactionServiceImpl implements FeedReactionService {

  private final FeedReactionRepository reactions;
  private final FeedService feed;
  private final PlayerDirectory players;
  private final TimeSource time;
  private final Cache<Long, Window> windows = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(5)).maximumSize(10_000).build();

  private static final class Window {
    Instant start;
    int count;
  }

  public FeedReactionServiceImpl(FeedReactionRepository reactions, FeedService feed, PlayerDirectory players,
                                 TimeSource time) {
    this.reactions = reactions;
    this.feed = feed;
    this.players = players;
    this.time = time;
  }

  @Override
  public FeedReactionsDto react(long playerId, String itemId, Reaction reaction) {
    Objects.requireNonNull(reaction, "reaction");
    ItemRef item = visible(itemId);
    charge(playerId);
    reactions.set(item.id(), playerId, reaction, time.now());
    FeedReactionsDto dto = feed.reactionsOf(item.id(), item.publicId());
    feed.publishReactions(dto);
    return dto;
  }

  @Override
  public FeedReactionsDto unreact(long playerId, String itemId) {
    ItemRef item = visible(itemId);
    charge(playerId);
    boolean removed = reactions.remove(item.id(), playerId);
    FeedReactionsDto dto = feed.reactionsOf(item.id(), item.publicId());
    if (removed) {
      feed.publishReactions(dto);
    }
    return dto;
  }

  /** One reaction change of {@code playerId} (§16.11); {@code RATE_LIMITED} once the minute's changes are used. */
  private void charge(long playerId) {
    Instant now = time.now();
    Window w = windows.get(playerId, k -> new Window());
    synchronized (w) {
      if (w.start == null || !now.isBefore(w.start.plusSeconds(60)) || now.isBefore(w.start)) {
        w.start = now;
        w.count = 0;
      }
      if (w.count >= MAX_CHANGES_PER_MINUTE) {
        long wait = Math.max(1, Duration.between(now, w.start.plusSeconds(60)).toSeconds());
        throw ApiException.of(ErrorCode.RATE_LIMITED, "At most " + MAX_CHANGES_PER_MINUTE
            + " reaction changes per minute. Retry in " + wait + " s.").withRetryAfter(wait);
      }
      w.count++;
    }
  }

  /** A visible item of a listed player, else {@code NOT_FOUND} (the same answer for "not yet visible"). */
  private ItemRef visible(String itemId) {
    ItemRef item = reactions.visibleItem(itemId, time.now()).orElse(null);
    PlayerRow owner = item == null ? null : players.byId(item.playerId()).orElse(null);
    if (item == null || owner == null || !owner.listed()) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No feed item '" + Text.clip(itemId, 40) + "'.");
    }
    return item;
  }
}
