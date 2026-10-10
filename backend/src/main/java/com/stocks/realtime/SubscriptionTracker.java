package com.stocks.realtime;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.realtime.StompDestinations.Kind;
import com.stocks.realtime.StompDestinations.Parsed;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Who is subscribed to what (§19), and the market-data leases those subscriptions hold (§15.5):
 * <ul>
 *   <li><b>Sessions.</b> Every STOMP session of a player (at most {@link #MAX_SESSIONS_PER_PLAYER}, §17.5), with the
 *       player's STOMP user name (the public id, {@code PlayerAuthenticationToken.getName()}), so pushes to
 *       {@code /user/queue/*} skip players who are not connected.</li>
 *   <li><b>Subscriptions.</b> Per session, by STOMP subscription id; at most
 *       {@link #MAX_MARKET_SUBSCRIPTIONS_PER_SESSION} quote, tick and bars subscriptions together. Global counts per
 *       destination tell the publishers whether anyone listens.</li>
 *   <li><b>Leases.</b> {@code /topic/quote.{SYM}} and {@code /topic/bars.{SYM}.1Min} hold a VIEWING lease,
 *       {@code /topic/tick.{SYM}} a LIST lease, owner key {@code ws:<sessionId>}. A session holds one lease per symbol
 *       at the highest priority any of its subscriptions needs; unsubscribing the last one releases it, and a
 *       disconnect releases everything ({@code releaseOwner}). Bots' {@code get_quotes} take a {@link #BOT_LEASE_TTL}
 *       LIST lease per symbol, owner {@code bot:<playerId>} ({@link #touchBotLeases}).</li>
 * </ul>
 * Thread-safe: the inbound channel may process different sessions concurrently (frames of one session stay in
 * order); each session's state is guarded by its own monitor.
 */
@Component
public class SubscriptionTracker {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionTracker.class);

  /** §17.5: STOMP sessions per player. */
  public static final int MAX_SESSIONS_PER_PLAYER = 5;
  /** §17.5 and §19: quote, tick and bars subscriptions per session. */
  public static final int MAX_MARKET_SUBSCRIPTIONS_PER_SESSION = 60;
  /** §20.3 {@code get_quotes}: the LIST lease a bot's quote request holds. */
  public static final Duration BOT_LEASE_TTL = Duration.ofSeconds(60);

  public enum ConnectResult { OK, TOO_MANY_SESSIONS }

  public enum SubscribeResult { OK, DUPLICATE_ID, CAP_REACHED, UNKNOWN_SESSION }

  private record Subscription(String id, Parsed destination) {}

  /** One symbol's lease of one session: how many subscriptions need each priority, and the handle held. */
  private static final class SymbolLease {
    int viewing;
    int list;
    LeasePriority held;
    LeaseHandle handle;

    LeasePriority wanted() {
      return viewing > 0 ? LeasePriority.VIEWING : list > 0 ? LeasePriority.LIST : null;
    }
  }

  private static final class Session {
    final String id;
    final long playerId;
    final String user;
    final boolean admin;
    final Map<String, Subscription> subscriptions = new HashMap<>();
    final Map<String, SymbolLease> leases = new HashMap<>();
    int marketSubscriptions;
    boolean closed;

    Session(String id, long playerId, String user, boolean admin) {
      this.id = id;
      this.playerId = playerId;
      this.user = user;
      this.admin = admin;
    }
  }

  private record BotLease(LeaseHandle handle, Instant expiresAt) {}

  private final MarketDataService marketData;
  private final TimeSource time;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<Long, Set<String>> sessionsByPlayer = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> subscribers = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> symbolWatchers = new ConcurrentHashMap<>();
  private final Map<String, BotLease> botLeases = new ConcurrentHashMap<>();

  public SubscriptionTracker(MarketDataService marketData, TimeSource time) {
    this.marketData = marketData;
    this.time = time;
  }

  // ------------------------------------------------------------------------------------------- sessions

  /** Registers a STOMP session at CONNECT; refuses a sixth concurrent session of the same player. */
  public ConnectResult connect(String sessionId, long playerId, String user, boolean admin) {
    Set<String> mine = sessionsByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
    synchronized (mine) {
      if (!mine.contains(sessionId) && mine.size() >= MAX_SESSIONS_PER_PLAYER) {
        return ConnectResult.TOO_MANY_SESSIONS;
      }
      mine.add(sessionId);
      sessions.putIfAbsent(sessionId, new Session(sessionId, playerId, user, admin));
    }
    return ConnectResult.OK;
  }

  /** Drops a session and everything it held (DISCONNECT frame or socket close; idempotent). */
  public void disconnect(String sessionId) {
    Session s = sessions.remove(sessionId);
    if (s == null) {
      return;
    }
    synchronized (s) {
      s.closed = true;
      for (Subscription sub : s.subscriptions.values()) {
        count(subscribers, sub.destination().destination(), -1);
        if (sub.destination().kind().isMarket()) {
          count(symbolWatchers, sub.destination().symbol(), -1);
        }
      }
      s.subscriptions.clear();
      for (SymbolLease lease : s.leases.values()) {
        close(lease.handle);
      }
      s.leases.clear();
      s.marketSubscriptions = 0;
    }
    Set<String> mine = sessionsByPlayer.get(s.playerId);
    if (mine != null) {
      synchronized (mine) {
        mine.remove(sessionId);
        if (mine.isEmpty()) {
          sessionsByPlayer.remove(s.playerId, mine);
        }
      }
    }
    try {
      marketData.releaseOwner(ownerKey(sessionId));
    } catch (RuntimeException e) {
      log.warn("releaseOwner({}) failed: {}", ownerKey(sessionId), e.toString());
    }
  }

  /** The owner key of a session's leases (§15.5). */
  public static String ownerKey(String sessionId) {
    return "ws:" + sessionId;
  }

  /** The owner key of a bot's {@code get_quotes} leases (§15.5). */
  public static String botOwnerKey(long playerId) {
    return "bot:" + playerId;
  }

  // ------------------------------------------------------------------------------------------- subscriptions

  /**
   * Records a SUBSCRIBE that {@link StompAuthInterceptor} allowed, and takes or upgrades the symbol's lease.
   * {@code CAP_REACHED}: the session already has {@link #MAX_MARKET_SUBSCRIPTIONS_PER_SESSION} market subscriptions.
   */
  public SubscribeResult subscribe(String sessionId, String subscriptionId, Parsed destination) {
    Session s = sessions.get(sessionId);
    if (s == null) {
      return SubscribeResult.UNKNOWN_SESSION;
    }
    synchronized (s) {
      if (s.closed) {
        return SubscribeResult.UNKNOWN_SESSION;
      }
      if (s.subscriptions.containsKey(subscriptionId)) {
        return SubscribeResult.DUPLICATE_ID;
      }
      Kind kind = destination.kind();
      if (kind.isMarket() && s.marketSubscriptions >= MAX_MARKET_SUBSCRIPTIONS_PER_SESSION) {
        return SubscribeResult.CAP_REACHED;
      }
      s.subscriptions.put(subscriptionId, new Subscription(subscriptionId, destination));
      count(subscribers, destination.destination(), +1);
      if (kind.isMarket()) {
        s.marketSubscriptions++;
        count(symbolWatchers, destination.symbol(), +1);
        SymbolLease lease = s.leases.computeIfAbsent(destination.symbol(), k -> new SymbolLease());
        if (kind.leasePriority() == LeasePriority.VIEWING) {
          lease.viewing++;
        } else {
          lease.list++;
        }
        syncLease(s, destination.symbol(), lease);
      }
      return SubscribeResult.OK;
    }
  }

  /** An UNSUBSCRIBE (unknown ids are ignored). */
  public void unsubscribe(String sessionId, String subscriptionId) {
    Session s = sessions.get(sessionId);
    if (s == null) {
      return;
    }
    synchronized (s) {
      Subscription sub = s.subscriptions.remove(subscriptionId);
      if (sub == null) {
        return;
      }
      Parsed d = sub.destination();
      count(subscribers, d.destination(), -1);
      if (d.kind().isMarket()) {
        s.marketSubscriptions--;
        count(symbolWatchers, d.symbol(), -1);
        SymbolLease lease = s.leases.get(d.symbol());
        if (lease != null) {
          if (d.kind().leasePriority() == LeasePriority.VIEWING) {
            lease.viewing = Math.max(0, lease.viewing - 1);
          } else {
            lease.list = Math.max(0, lease.list - 1);
          }
          syncLease(s, d.symbol(), lease);
          if (lease.wanted() == null) {
            s.leases.remove(d.symbol());
          }
        }
      }
    }
  }

  /** Holds the lease at the priority the session's subscriptions need now (close, then re-lease on a change). */
  private void syncLease(Session s, String symbol, SymbolLease lease) {
    LeasePriority wanted = lease.wanted();
    if (wanted == lease.held) {
      return;
    }
    close(lease.handle);
    lease.handle = null;
    lease.held = null;
    if (wanted != null) {
      try {
        lease.handle = marketData.lease(symbol, wanted, ownerKey(s.id));
        lease.held = wanted;
      } catch (RuntimeException e) {
        log.warn("lease({}, {}, {}) failed: {}", symbol, wanted, ownerKey(s.id), e.toString());
      }
    }
  }

  // ------------------------------------------------------------------------------------------- queries

  /** Subscriptions to exactly {@code destination}, over all sessions (e.g. {@code /topic/quote.AAPL}). */
  public int subscribers(String destination) {
    AtomicInteger n = subscribers.get(destination);
    return n == null ? 0 : n.get();
  }

  /** True if any session subscribes to a quote, tick or bars topic of {@code symbol}. */
  public boolean watched(String symbol) {
    AtomicInteger n = symbolWatchers.get(symbol);
    return n != null && n.get() > 0;
  }

  /** Every symbol some session watches. */
  public Set<String> watchedSymbols() {
    Set<String> out = new LinkedHashSet<>();
    symbolWatchers.forEach((sym, n) -> {
      if (n.get() > 0) {
        out.add(sym);
      }
    });
    return out;
  }

  /** The STOMP user name of a connected player (their public id), if they have a session. */
  public Optional<String> userOf(long playerId) {
    Set<String> mine = sessionsByPlayer.get(playerId);
    if (mine == null) {
      return Optional.empty();
    }
    for (String sessionId : mine) {
      Session s = sessions.get(sessionId);
      if (s != null) {
        return Optional.of(s.user);
      }
    }
    return Optional.empty();
  }

  public boolean isConnected(long playerId) {
    return userOf(playerId).isPresent();
  }

  /** Number of STOMP sessions of a player. */
  public int sessionCount(long playerId) {
    Set<String> mine = sessionsByPlayer.get(playerId);
    return mine == null ? 0 : mine.size();
  }

  /** Players with at least one session subscribed to {@code destination} (e.g. {@code /user/queue/account}). */
  public Set<Long> playersSubscribedTo(String destination) {
    Set<Long> out = new HashSet<>();
    for (Session s : sessions.values()) {
      synchronized (s) {
        for (Subscription sub : s.subscriptions.values()) {
          if (sub.destination().destination().equals(destination)) {
            out.add(s.playerId);
            break;
          }
        }
      }
    }
    return out;
  }

  /** The market subscriptions of one session (for tests and diagnostics). */
  public int marketSubscriptions(String sessionId) {
    Session s = sessions.get(sessionId);
    if (s == null) {
      return 0;
    }
    synchronized (s) {
      return s.marketSubscriptions;
    }
  }

  // ------------------------------------------------------------------------------------------- bot leases

  /**
   * §20.3 {@code get_quotes}: a LIST lease per symbol for {@link #BOT_LEASE_TTL}, owner {@code bot:<playerId>};
   * calling again extends it. Expired leases are released by {@link #expireBotLeases()}.
   */
  public void touchBotLeases(long playerId, Collection<String> symbols) {
    Instant expires = time.now().plus(BOT_LEASE_TTL);
    String owner = botOwnerKey(playerId);
    for (String symbol : symbols) {
      String key = owner + "|" + symbol;
      botLeases.compute(key, (k, existing) -> {
        if (existing != null) {
          return new BotLease(existing.handle(), expires);
        }
        try {
          return new BotLease(marketData.lease(symbol, LeasePriority.LIST, owner), expires);
        } catch (RuntimeException e) {
          log.warn("lease({}, LIST, {}) failed: {}", symbol, owner, e.toString());
          return null;
        }
      });
    }
  }

  /** Releases bot leases whose TTL has passed; returns how many. */
  public int expireBotLeases() {
    Instant now = time.now();
    List<String> expired = new ArrayList<>();
    botLeases.forEach((k, v) -> {
      if (!v.expiresAt().isAfter(now)) {
        expired.add(k);
      }
    });
    int n = 0;
    for (String key : expired) {
      BotLease[] removed = new BotLease[1];
      botLeases.computeIfPresent(key, (k, v) -> {
        if (v.expiresAt().isAfter(now)) {
          return v;   // extended meanwhile
        }
        removed[0] = v;
        return null;
      });
      if (removed[0] != null) {
        close(removed[0].handle());
        n++;
      }
    }
    return n;
  }

  /** Bot lease keys ({@code bot:<playerId>|SYM}) currently held (for tests). */
  public Set<String> botLeaseKeys() {
    return Set.copyOf(botLeases.keySet());
  }

  // ------------------------------------------------------------------------------------------- helpers

  private static void count(Map<String, AtomicInteger> counts, String key, int delta) {
    if (key == null) {
      return;
    }
    counts.compute(key, (k, n) -> {
      int next = (n == null ? 0 : n.get()) + delta;
      return next <= 0 ? null : new AtomicInteger(next);
    });
  }

  private static void close(LeaseHandle handle) {
    if (handle == null) {
      return;
    }
    try {
      handle.close();
    } catch (RuntimeException e) {
      log.warn("Closing a lease failed: {}", e.toString());
    }
  }
}
