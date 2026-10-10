package com.stocks.league;

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
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * {@link FeedReactionService} (§16.11, Phase 2): one reaction per player per visible feed item, written on the request
 * thread with one auto-committed statement (social data, not money: no engine, no audit). Each change answers the item's reactions and
 * pushes them on {@code /topic/feed} after the write. Humans react from the feed; bots through MCP
 * {@code react_to_feed_item}, within their normal read limits.
 */
@Service
public class FeedReactionServiceImpl implements FeedReactionService {

  private final FeedReactionRepository reactions;
  private final FeedService feed;
  private final PlayerDirectory players;
  private final TimeSource time;

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
    reactions.set(item.id(), playerId, reaction, time.now());
    FeedReactionsDto dto = feed.reactionsOf(item.id(), item.publicId());
    feed.publishReactions(dto);
    return dto;
  }

  @Override
  public FeedReactionsDto unreact(long playerId, String itemId) {
    ItemRef item = visible(itemId);
    boolean removed = reactions.remove(item.id(), playerId);
    FeedReactionsDto dto = feed.reactionsOf(item.id(), item.publicId());
    if (removed) {
      feed.publishReactions(dto);
    }
    return dto;
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
