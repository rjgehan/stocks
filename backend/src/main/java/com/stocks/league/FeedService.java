package com.stocks.league;

import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedPayloadDto;
import com.stocks.league.FeedRepository.FeedRow;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import java.time.Duration;
import java.time.Instant;
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
 * players hidden from the league (DISABLED) are skipped.
 */
@Service
public class FeedService implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(FeedService.class);

  public static final String NAME = "FEED_PUBLISHER";
  public static final String TOPIC = "/topic/feed";
  static final Duration INTERVAL = Duration.ofSeconds(5);

  private final FeedRepository feed;
  private final PlayerDirectory players;
  private final JsonMapper json;
  private final TimeSource time;
  private final ObjectProvider<SimpMessageSendingOperations> messaging;
  private volatile Instant published;

  public FeedService(FeedRepository feed, PlayerDirectory players, JsonMapper json, TimeSource time,
                     ObjectProvider<SimpMessageSendingOperations> messaging) {
    this.feed = feed;
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
    return new Page<>(page.items().stream()
        .filter(r -> all.containsKey(r.playerId()) && all.get(r.playerId()).listed())
        .map(r -> item(r, all.get(r.playerId())))
        .toList(), page.nextCursor());
  }

  FeedItemDto item(FeedRow r, PlayerRow player) {
    FeedPayloadDto payload = json.readValue(r.payload(), FeedPayloadDto.class);
    return new FeedItemDto(r.publicId(), r.createdAt(), r.kind(), player.summary(), r.symbol(), payload);
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
    int n = 0;
    for (FeedRow r : feed.visibleBetween(from, now)) {
      PlayerRow p = all.get(r.playerId());
      if (p == null || !p.listed()) {
        continue;
      }
      try {
        ops.convertAndSend(TOPIC, item(r, p));
        n++;
      } catch (RuntimeException e) {
        log.debug("Could not publish {}: {}", TOPIC, e.toString());
      }
    }
    return n;
  }
}
