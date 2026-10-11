package com.stocks.league;

import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.portfolio.FeedReactionService;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedPayloadDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.FeedReactionDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.ReactorDto;
import com.stocks.league.FeedReactionRepository.ReactionRow;
import com.stocks.league.FeedRepository.FeedRow;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The league feed (§18.2 {@code GET /feed}, MCP {@code get_league_feed}): only rows with {@code visible_at ≤ now},
 * newest first. Also the feed visibility publisher ({@code FEED_PUBLISHER}, a 5 s {@link PeriodicTask}, §19): each
 * row is pushed to {@code /topic/feed} once, when it becomes visible (when a STOMP broker is configured). Rows of
 * players hidden from the league (DISABLED) are skipped. Phase 2 (§16.11): every item carries its reactions, and a
 * reaction change pushes the item's {@code FeedReactions} on the same topic.
 */
@Service
public class FeedService implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(FeedService.class);

  public static final String NAME = "FEED_PUBLISHER";
  public static final String TOPIC = "/topic/feed";
  static final Duration INTERVAL = Duration.ofSeconds(5);

  private final FeedRepository feed;
  private final FeedReactionRepository reactions;
  private final PlayerDirectory players;
  private final JsonMapper json;
  private final TimeSource time;
  private final ObjectProvider<SimpMessageSendingOperations> messaging;
  private volatile Instant published;

  public FeedService(FeedRepository feed, FeedReactionRepository reactions, PlayerDirectory players, JsonMapper json,
                     TimeSource time, ObjectProvider<SimpMessageSendingOperations> messaging) {
    this.feed = feed;
    this.reactions = reactions;
    this.players = players;
    this.json = json;
    this.time = time;
    this.messaging = messaging;
  }

  public Page<FeedItemDto> page(Cursor cursor, int limit) {
    int n = Math.max(1, Math.min(limit, Page.MAX_LIMIT));
    Map<Long, PlayerRow> all = players.all();
    List<FeedRow> rows = feed.page(time.now(), cursor, n + 1);
    Page<FeedRow> page = Page.fromLookahead(rows, n, r -> Cursor.of(r.visibleAt(), r.id()));
    List<FeedRow> listed = page.items().stream()
        .filter(r -> all.containsKey(r.playerId()) && all.get(r.playerId()).listed())
        .toList();
    Map<Long, List<ReactionRow>> byItem = reactions.forItems(listed.stream().map(FeedRow::id).toList());
    return new Page<>(listed.stream()
        .map(r -> item(r, all.get(r.playerId()), reactionDtos(byItem.getOrDefault(r.id(), List.of()), all)))
        .toList(), page.nextCursor());
  }

  FeedItemDto item(FeedRow r, PlayerRow player, List<FeedReactionDto> itemReactions) {
    FeedPayloadDto payload = json.readValue(r.payload(), FeedPayloadDto.class);
    return new FeedItemDto(r.publicId(), r.createdAt(), r.kind(), player.summary(), r.symbol(), payload,
        itemReactions);
  }

  // ------------------------------------------------------------------------------------------- reactions (§16.11)

  /** The current reactions of one feed item (by internal id), as the feed and {@code /topic/feed} show them. */
  public FeedReactionsDto reactionsOf(long feedEventId, String itemId) {
    Map<Long, PlayerRow> all = players.all();
    List<ReactionRow> rows = reactions.forItems(List.of(feedEventId)).getOrDefault(feedEventId, List.of());
    return new FeedReactionsDto(itemId, reactionDtos(rows, all));
  }

  /**
   * Groups an item's reaction rows (oldest first) by emoji in {@link Reaction} order, emojis with at least one listed
   * player only; reactions of players hidden from the league (DISABLED) are left out.
   */
  static List<FeedReactionDto> reactionDtos(List<ReactionRow> rows, Map<Long, PlayerRow> players) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Reaction, List<ReactorDto>> by = new EnumMap<>(Reaction.class);
    for (ReactionRow r : rows) {
      PlayerRow p = players.get(r.playerId());
      if (p == null || !p.listed()) {
        continue;
      }
      by.computeIfAbsent(r.reaction(), k -> new ArrayList<>())
          .add(new ReactorDto(p.handle(), p.displayName(), p.kind()));
    }
    List<FeedReactionDto> out = new ArrayList<>();
    by.forEach((reaction, who) -> out.add(new FeedReactionDto(reaction, who.size(), List.copyOf(who))));
    return List.copyOf(out);
  }

  /** Pushes an item's reactions on {@code /topic/feed} (§19), when a STOMP broker is configured. */
  public void publishReactions(FeedReactionsDto dto) {
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (ops == null) {
      return;
    }
    try {
      // marked, so the SSE tap streams it as a non-resumable feedReactions event (§19.3), never in the ring buffer
      ops.convertAndSend(TOPIC, dto,
          Map.<String, Object>of(FeedReactionService.STOMP_KIND_HEADER, FeedReactionService.REACTIONS_KIND));
    } catch (RuntimeException e) {
      log.debug("Could not publish {}: {}", TOPIC, e.toString());
    }
  }

  // ------------------------------------------------------------------------------------------- PeriodicTask

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Duration interval() {
    return INTERVAL;
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public void tick() {
    publishNewlyVisible();
  }

  /** Pushes rows that became visible since the last tick; returns how many. */
  public int publishNewlyVisible() {
    Instant now = time.now();
    Instant from = published;
    published = now;
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (from == null || ops == null) {
      return 0;                                      // first tick only sets the watermark
    }
    Map<Long, PlayerRow> all = players.all();
    List<FeedRow> rows = feed.visibleBetween(from, now);
    Map<Long, List<ReactionRow>> byItem = rows.isEmpty() ? Map.of()
        : reactions.forItems(rows.stream().map(FeedRow::id).toList());
    int n = 0;
    for (FeedRow r : rows) {
      PlayerRow p = all.get(r.playerId());
      if (p == null || !p.listed()) {
        continue;
      }
      try {
        ops.convertAndSend(TOPIC, item(r, p, reactionDtos(byItem.getOrDefault(r.id(), List.of()), all)));
        n++;
      } catch (RuntimeException e) {
        log.debug("Could not publish {}: {}", TOPIC, e.toString());
      }
    }
    return n;
  }
}
