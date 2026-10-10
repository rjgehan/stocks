package com.stocks.realtime.stream;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.realtime.dto.LeaderboardUpdateDto;
import com.stocks.contract.realtime.dto.StreamByeDto;
import com.stocks.contract.realtime.dto.StreamHelloDto;
import com.stocks.contract.realtime.dto.StreamResetDto;
import com.stocks.contract.realtime.dto.StreamTopic;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.realtime.MarketSessionTopic;
import com.stocks.realtime.QuoteMessages;
import com.stocks.realtime.StompDestinations;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSE stream's hub (§19.1): open streams and their caps, the ring buffer behind {@code Last-Event-ID}, and the
 * audience filter. Inputs: {@code UserPush} hands it a player's {@code order}, {@code account} and
 * {@code notification} payloads ({@link #publishPrivate}), {@link StreamTopicTap} the broadcast {@code session},
 * {@code leaderboard} and {@code feed} payloads ({@link #publishPublic}), and {@link StreamQuotes} quote changes.
 *
 * <p><b>Ids and resume.</b> Only resumable events ({@code order}, {@code notification}, {@code feed}) get an id,
 * {@code <epoch>-<seq>}: {@code epoch} is random per server start, {@code seq} one counter for every stream. Each is
 * kept in one ring buffer ({@code buffer-size}, {@code buffer-max-age}) with its audience (one player, or everyone).
 * A reconnect with a {@code Last-Event-ID} of this epoch that is still covered by the buffer replays the events after
 * it that the caller may see and asked for; otherwise the stream starts with {@code reset} (RESTARTED, EXPIRED or
 * UNKNOWN). A player's private events are recorded while the player has a stream open or closed one within
 * {@code buffer-max-age}; once that window lapsed, the next private event marks the player's older ids as expired.
 *
 * <p><b>Isolation.</b> A private event is offered only to the streams of its player and replayed only to them; the
 * same audience check applies to live events and replays.
 */
@Component
public class StreamHub implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(StreamHub.class);
  private static final String EPOCH_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

  /** The §19.1 event names. */
  public static final String ORDER = "order";
  public static final String ACCOUNT = "account";
  public static final String NOTIFICATION = "notification";
  public static final String FEED = "feed";
  public static final String LEADERBOARD = "leaderboard";
  public static final String SESSION = "session";
  public static final String QUOTE = "quote";

  /** A resumable event in the ring buffer; {@code audience} null = everyone. */
  record Buffered(long seq, Instant at, Long audience, StreamTopic topic, String frame) {}

  /** What a §19 destination means on the stream. */
  record Route(StreamTopic topic, String event, boolean resumable) {}

  private final StreamProperties properties;
  private final TimeSource time;
  private final JsonMapper json;
  private final MarketDataService marketData;
  private final QuoteMessages quotes;
  private final ObjectProvider<AccountQueries> accounts;
  private final ObjectProvider<LeaderboardService> leaderboard;
  private final ObjectProvider<MarketSessionTopic> sessions;
  private final PlayerQueries players;
  private final JdbcClient jdbc;
  private final String epoch;
  private final Object lock = new Object();
  private final ArrayDeque<Buffered> buffer = new ArrayDeque<>();
  private long seq;
  private long evictedThrough;
  private final Map<String, StreamConnection> connections = new ConcurrentHashMap<>();
  private final Map<String, Set<StreamConnection>> bySymbol = new ConcurrentHashMap<>();
  /** When a player's last stream closed: private events are still recorded within {@code buffer-max-age}. */
  private final Map<Long, Instant> lastClosed = new ConcurrentHashMap<>();
  /** The highest seq after which a private event of the player was not recorded (ids up to it are expired). */
  private final Map<Long, Long> missedAfter = new ConcurrentHashMap<>();
  private final AtomicInteger counter = new AtomicInteger();
  private volatile boolean running;
  private volatile Thread revalidator;

  public StreamHub(StreamProperties properties, TimeSource time, JsonMapper json, MarketDataService marketData,
                   QuoteMessages quotes, ObjectProvider<AccountQueries> accounts,
                   ObjectProvider<LeaderboardService> leaderboard, ObjectProvider<MarketSessionTopic> sessions,
                   PlayerQueries players, JdbcClient jdbc) {
    this.properties = properties == null ? StreamProperties.defaults() : properties;
    this.time = time;
    this.json = json;
    this.marketData = marketData;
    this.quotes = quotes;
    this.accounts = accounts;
    this.leaderboard = leaderboard;
    this.sessions = sessions;
    this.players = players;
    this.jdbc = jdbc;
    this.epoch = randomEpoch();
  }

  private static String randomEpoch() {
    SecureRandom random = new SecureRandom();
    StringBuilder b = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
      b.append(EPOCH_ALPHABET.charAt(random.nextInt(EPOCH_ALPHABET.length())));
    }
    return b.toString();
  }

  public StreamProperties properties() {
    return properties;
  }

  /** This server start's id prefix. */
  public String epoch() {
    return epoch;
  }

  // ------------------------------------------------------------------------------------------- open

  /**
   * Opens a stream for {@code caller}: checks the caps (429 {@code RATE_LIMITED}, {@code Retry-After: 30}), queues
   * {@code retry}, {@code hello}, then {@code reset} or the replayed events, registers the stream for live events
   * (atomically with the replay, so nothing falls between them), then the snapshots ({@code session},
   * {@code account}, {@code leaderboard}, the current {@code quote}s) and starts the sender.
   */
  public StreamConnection open(PlayerPrincipal caller, Set<StreamTopic> topics, List<String> symbols,
                               String lastEventId, StreamSink sink) {
    String id = "sse_" + epoch + "_" + counter.incrementAndGet();
    StreamConnection c = new StreamConnection(id, caller.playerId(), caller.apiKeyId(), topics, symbols, sink,
        properties, System::nanoTime, this::quoteFrame, this::heartbeatFrame, this::byeFrame);
    synchronized (lock) {
      if (connections.size() >= properties.maxConnections()) {
        throw tooMany("The server has the most streams it allows (" + properties.maxConnections() + ").");
      }
      long mine = connections.values().stream().filter(o -> o.playerId() == caller.playerId()).count();
      if (mine >= properties.maxConnectionsPerPlayer()) {
        throw tooMany("At most " + properties.maxConnectionsPerPlayer() + " open streams per player.");
      }
      Instant now = time.now();
      trim(now);
      StreamResetDto.Reason reset = null;
      List<String> replay = List.of();
      String last = lastEventId == null || lastEventId.isBlank() ? null : lastEventId.strip();
      if (last != null) {
        Long lastSeq = parseSeq(last);
        if (lastSeq == null) {
          reset = isOtherEpoch(last) ? StreamResetDto.Reason.RESTARTED : StreamResetDto.Reason.UNKNOWN;
        } else if (lastSeq > seq) {
          reset = StreamResetDto.Reason.UNKNOWN;
        } else if (lastSeq < evictedThrough || missedAfter.getOrDefault(caller.playerId(), -1L) >= lastSeq) {
          reset = StreamResetDto.Reason.EXPIRED;
        } else {
          replay = new ArrayList<>();
          for (Buffered b : buffer) {
            if (b.seq() > lastSeq && visibleTo(b, caller.playerId()) && topics.contains(b.topic())) {
              replay.add(b.frame());
            }
          }
        }
      }
      c.offer("retry: " + properties.retryMs() + "\n\n");
      c.offer(frame(null, "hello", json.writeValueAsString(new StreamHelloDto(id,
          topics.stream().sorted().toList(), symbols, last != null && reset == null, replay.size(),
          (int) properties.heartbeat().toSeconds(), (int) properties.maxAge().toSeconds()))));
      if (reset != null) {
        c.offer(frame(null, "reset", json.writeValueAsString(new StreamResetDto(reset, clip(last)))));
      }
      for (String frame : replay) {
        c.offer(frame);
      }
      connections.put(id, c);
      for (String symbol : symbols) {
        bySymbol.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet()).add(c);
      }
      lastClosed.remove(caller.playerId());
      missedAfter.remove(caller.playerId());
    }
    c.onClosed(() -> closed(c));
    snapshots(c, caller);
    for (String symbol : symbols) {
      try {
        marketData.lease(symbol, LeasePriority.LIST, ownerKey(id));
      } catch (RuntimeException e) {
        log.debug("SSE lease {} failed: {}", symbol, e.toString());
      }
      c.quoteChanged(symbol);                          // the current quote at connect
    }
    c.start();
    return c;
  }

  private static ApiException tooMany(String message) {
    return ApiException.of(ErrorCode.RATE_LIMITED, message + " Close one or retry later.").withRetryAfter(30);
  }

  /** {@code sse:<connectionId>}: the market-data lease owner of a stream (§15.5). */
  static String ownerKey(String connectionId) {
    return "sse:" + connectionId;
  }

  private static boolean visibleTo(Buffered b, long playerId) {
    return b.audience() == null || b.audience() == playerId;
  }

  /** The seq of an id of this epoch; null when malformed or of another epoch. */
  Long parseSeq(String id) {
    int dash = id.lastIndexOf('-');
    if (dash <= 0 || !id.substring(0, dash).equals(epoch)) {
      return null;
    }
    try {
      long s = Long.parseLong(id.substring(dash + 1));
      return s < 0 ? null : s;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** A well-formed id of another server start. */
  private boolean isOtherEpoch(String id) {
    int dash = id.lastIndexOf('-');
    if (dash <= 0 || id.length() > 40) {
      return false;
    }
    String e = id.substring(0, dash);
    return !e.equals(epoch) && e.chars().allMatch(ch -> EPOCH_ALPHABET.indexOf(ch) >= 0)
        && id.substring(dash + 1).chars().allMatch(Character::isDigit) && dash + 1 < id.length();
  }

  private static String clip(String s) {
    return s == null ? null : s.length() <= 64 ? s : s.substring(0, 64);
  }

  private void snapshots(StreamConnection c, PlayerPrincipal caller) {
    if (c.wants(StreamTopic.SESSION)) {
      snapshot(c, SESSION, () -> {
        MarketSessionTopic topic = sessions.getIfAvailable();
        return topic == null ? null : topic.current(time.now()).orElse(null);
      });
    }
    if (c.wants(StreamTopic.ACCOUNT)) {
      snapshot(c, ACCOUNT, () -> {
        AccountQueries q = accounts.getIfAvailable();
        if (q == null) {
          return null;
        }
        AccountDto a = q.account(q.activeAccountId(caller.playerId()));
        return a;
      });
    }
    if (c.wants(StreamTopic.LEADERBOARD)) {
      snapshot(c, LEADERBOARD, () -> {
        LeaderboardService l = leaderboard.getIfAvailable();
        if (l == null) {
          return null;
        }
        LeaderboardDto board = l.board(LeaderboardPeriod.WEEK, LeaderboardMetric.RETURN, KindFilter.ALL, 1,
            caller.playerId());
        return board == null ? null : new LeaderboardUpdateDto(board.asOf());
      });
    }
  }

  private void snapshot(StreamConnection c, String event, Supplier<Object> payload) {
    try {
      Object p = payload.get();
      if (p != null) {
        c.offer(frame(null, event, json.writeValueAsString(p)));
      }
    } catch (RuntimeException e) {
      log.debug("SSE {} snapshot for {} skipped: {}", event, c.id(), e.toString());
    }
  }

  private void closed(StreamConnection c) {
    try {
      marketData.releaseOwner(ownerKey(c.id()));
    } catch (RuntimeException e) {
      log.debug("SSE lease release {} failed: {}", c.id(), e.toString());
    }
    synchronized (lock) {
      if (connections.remove(c.id()) == null) {
        return;
      }
      for (String symbol : c.symbols()) {
        Set<StreamConnection> set = bySymbol.get(symbol);
        if (set != null) {
          set.remove(c);
          if (set.isEmpty()) {
            bySymbol.remove(symbol, set);
          }
        }
      }
      if (connections.values().stream().noneMatch(o -> o.playerId() == c.playerId())) {
        lastClosed.put(c.playerId(), time.now());
      }
    }
  }

  // ------------------------------------------------------------------------------------------- events in

  /** The §19 user queue → stream route; null for queues the stream does not carry ({@code /queue/admin}). */
  static Route privateRoute(String queue) {
    return switch (queue) {
      case StompDestinations.ORDERS_QUEUE -> new Route(StreamTopic.ORDERS, ORDER, true);
      case StompDestinations.ACCOUNT_QUEUE -> new Route(StreamTopic.ACCOUNT, ACCOUNT, false);
      case StompDestinations.NOTIFICATIONS_QUEUE -> new Route(StreamTopic.ACCOUNT, NOTIFICATION, true);
      default -> null;
    };
  }

  /** The §19 broadcast topic → stream route; null for the others. */
  static Route publicRoute(String destination) {
    if (destination == null) {
      return null;
    }
    return switch (destination) {
      case StompDestinations.FEED_TOPIC -> new Route(StreamTopic.FEED, FEED, true);
      case StompDestinations.LEADERBOARD_TOPIC -> new Route(StreamTopic.LEADERBOARD, LEADERBOARD, false);
      case StompDestinations.SESSION_TOPIC -> new Route(StreamTopic.SESSION, SESSION, false);
      default -> null;
    };
  }

  /**
   * True when the player's private events must reach the stream side: a stream is open, or the last one closed
   * within {@code buffer-max-age} (the reconnect window). When the window has lapsed this also notes that the
   * player's older ids can no longer be resumed.
   */
  public boolean wantsPrivate(long playerId) {
    if (connections.isEmpty() && lastClosed.isEmpty()) {
      return false;
    }
    for (StreamConnection c : connections.values()) {
      if (c.playerId() == playerId) {
        return true;
      }
    }
    Instant closedAt = lastClosed.get(playerId);
    if (closedAt == null) {
      return false;
    }
    if (time.now().isBefore(closedAt.plus(properties.bufferMaxAge()))) {
      return true;
    }
    synchronized (lock) {
      if (lastClosed.remove(playerId) != null) {
        missedAfter.merge(playerId, seq, Math::max);
      }
    }
    return false;
  }

  /** A payload for one player's queue ({@code /queue/orders}, …), from {@code UserPush}. */
  public void publishPrivate(long playerId, String queue, Object payload) {
    Route route = privateRoute(queue);
    if (route == null || payload == null || !wantsPrivate(playerId)) {
      return;
    }
    String body;
    try {
      body = json.writeValueAsString(payload);
    } catch (RuntimeException e) {
      log.warn("SSE: cannot serialize {} for player {}: {}", queue, playerId, e.toString());
      return;
    }
    synchronized (lock) {
      String frame = route.resumable() ? record(route, playerId, body) : frame(null, route.event(), body);
      for (StreamConnection c : connections.values()) {
        if (c.playerId() == playerId && c.wants(route.topic())) {
          c.offer(frame);
        }
      }
    }
  }

  /** A broadcast payload ({@code /topic/feed}, …) as JSON, from {@link StreamTopicTap}. */
  public void publishPublic(String destination, String body) {
    Route route = publicRoute(destination);
    if (route == null || body == null) {
      return;
    }
    synchronized (lock) {
      if (!route.resumable() && connections.isEmpty()) {
        return;
      }
      String frame = route.resumable() ? record(route, null, body) : frame(null, route.event(), body);
      for (StreamConnection c : connections.values()) {
        if (c.wants(route.topic())) {
          c.offer(frame);
        }
      }
    }
  }

  /** Assigns the next id, buffers the event and returns its frame. Caller holds {@link #lock}. */
  private String record(Route route, Long audience, String body) {
    long s = ++seq;
    Instant now = time.now();
    String frame = frame(epoch + "-" + s, route.event(), body);
    buffer.addLast(new Buffered(s, now, audience, route.topic(), frame));
    trim(now);
    return frame;
  }

  /** Evicts beyond {@code buffer-size} and older than {@code buffer-max-age}. Caller holds {@link #lock}. */
  private void trim(Instant now) {
    Instant oldest = now.minus(properties.bufferMaxAge());
    while (!buffer.isEmpty()
        && (buffer.size() > properties.bufferSize() || buffer.peekFirst().at().isBefore(oldest))) {
      evictedThrough = Math.max(evictedThrough, buffer.removeFirst().seq());
    }
  }

  /** A quote of {@code symbol} changed (from {@link StreamQuotes}). */
  public void quoteChanged(String symbol) {
    Set<StreamConnection> watchers = symbol == null ? null : bySymbol.get(symbol);
    if (watchers != null) {
      for (StreamConnection c : watchers) {
        c.quoteChanged(symbol);
      }
    }
  }

  /** True when some stream watches {@code symbol}. */
  public boolean watches(String symbol) {
    return symbol != null && bySymbol.containsKey(symbol);
  }

  // ------------------------------------------------------------------------------------------- frames

  /** One SSE frame: optional {@code id}, {@code event}, and the JSON as {@code data} lines. */
  static String frame(String id, String event, String body) {
    StringBuilder b = new StringBuilder(body.length() + 48);
    if (id != null) {
      b.append("id: ").append(id).append('\n');
    }
    b.append("event: ").append(event).append('\n');
    for (String line : body.split("\n", -1)) {
      b.append("data: ").append(line.replace("\r", "")).append('\n');
    }
    return b.append('\n').toString();
  }

  private String quoteFrame(String symbol) {
    try {
      return frame(null, QUOTE, json.writeValueAsString(quotes.quote(marketData.quote(symbol))));
    } catch (RuntimeException e) {
      log.debug("SSE quote {} failed: {}", symbol, e.toString());
      return null;
    }
  }

  private String heartbeatFrame() {
    return ": hb " + time.now() + "\n\n";
  }

  private String byeFrame(StreamByeDto.Reason reason) {
    return frame(null, "bye", json.writeValueAsString(new StreamByeDto(reason)));
  }

  // ------------------------------------------------------------------------------------------- revalidation

  /** A frozen or disabled player's streams end at once (§19.1). */
  @EventListener
  public void on(PlayerStatusChanged e) {
    if (e != null && e.status() != PlayerStatus.ACTIVE) {
      for (StreamConnection c : connections.values()) {
        if (c.playerId() == e.playerId()) {
          c.close(StreamByeDto.Reason.REVOKED);
        }
      }
    }
  }

  /** Ends every stream whose key was revoked or expired or whose player is no longer ACTIVE; returns how many. */
  public int revalidate() {
    int closed = 0;
    Instant now = time.now();
    for (StreamConnection c : List.copyOf(connections.values())) {
      if (c.isDone()) {
        continue;
      }
      boolean ok;
      try {
        ok = players.status(c.playerId()) == PlayerStatus.ACTIVE && keyValid(c.apiKeyId(), now);
      } catch (RuntimeException e) {
        log.debug("SSE revalidation of {} failed: {}", c.id(), e.toString());
        continue;
      }
      if (!ok) {
        c.close(StreamByeDto.Reason.REVOKED);
        closed++;
      }
    }
    return closed;
  }

  private boolean keyValid(Long apiKeyId, Instant now) {
    if (apiKeyId == null) {
      return true;                                     // Phase 2 OAuth tokens: the JWT expiry ends them
    }
    record KeyState(Instant revokedAt, Instant expiresAt) {}
    Optional<KeyState> key = jdbc.sql("SELECT revoked_at, expires_at FROM api_key WHERE id = ?").param(apiKeyId)
        .query((rs, n) -> new KeyState(Jdbc.instant(rs, "revoked_at"), Jdbc.instant(rs, "expires_at"))).optional();
    return key.isPresent() && key.get().revokedAt() == null
        && (key.get().expiresAt() == null || key.get().expiresAt().isAfter(now));
  }

  // ------------------------------------------------------------------------------------------- lifecycle

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    revalidator = Thread.ofPlatform().daemon().name("sse-revalidate").start(() -> {
      long every = Math.max(TimeUnit.SECONDS.toNanos(1), properties.revalidateEvery().toNanos());
      while (running) {
        LockSupport.parkNanos(every);
        if (!running) {
          break;
        }
        try {
          revalidate();
        } catch (RuntimeException e) {
          log.warn("SSE revalidation failed: {}", e.toString());
        }
      }
    });
  }

  /** Ends every stream with {@code bye SHUTDOWN} before the web server's graceful shutdown waits for requests. */
  @Override
  public void stop() {
    running = false;
    Thread t = revalidator;
    if (t != null) {
      LockSupport.unpark(t);
    }
    Collection<StreamConnection> open = List.copyOf(connections.values());
    for (StreamConnection c : open) {
      c.close(StreamByeDto.Reason.SHUTDOWN);
    }
    for (StreamConnection c : open) {
      c.await(1_000);
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  // ------------------------------------------------------------------------------------------- introspection

  /** Open streams (tests and the admin's system view). */
  public int openStreams() {
    return connections.size();
  }

  /** Open streams of one player. */
  public List<StreamConnection> streamsOf(long playerId) {
    return connections.values().stream().filter(c -> c.playerId() == playerId)
        .sorted(Comparator.comparing(StreamConnection::id)).toList();
  }

  /** The newest event id, or null before the first resumable event (tests). */
  String lastEventId() {
    synchronized (lock) {
      return seq == 0 ? null : epoch + "-" + seq;
    }
  }

  /** Buffered events (tests). */
  int buffered() {
    synchronized (lock) {
      return buffer.size();
    }
  }
}
