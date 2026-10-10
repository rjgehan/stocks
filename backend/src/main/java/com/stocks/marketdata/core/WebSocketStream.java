package com.stocks.marketdata.core;

import com.stocks.common.time.TimeSource;
import com.stocks.marketdata.spi.ConnectionState;
import com.stocks.marketdata.spi.MarketStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One upstream WebSocket with the §15.5 socket policy: one owner per connection, exponential backoff from 5 s to 60 s,
 * a longer wait after a connection-limit error (Alpaca 406: 90 s), an authentication deadline, and a watchdog that
 * reconnects after 30 s of silence during venue hours. Subclasses speak the provider protocol: {@link #onConnected},
 * {@link #onMessage} and {@link #sendSubscriptionDiff}. All state changes run on one scheduler thread.
 */
public abstract class WebSocketStream implements MarketStream {

  /** Socket timings (§15.5); tests shrink them. */
  public record Timings(Duration initialBackoff, Duration maxBackoff, Duration afterConnectionLimit,
                        Duration authTimeout, Duration watchdogSilence, Duration authFailedBackoff,
                        Duration connectTimeout) {

    public static Timings defaults() {
      return new Timings(Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(90), Duration.ofSeconds(10),
          Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(10));
    }
  }

  protected final Logger log = LoggerFactory.getLogger(getClass());
  protected final String name;
  protected final TimeSource time;
  protected final Timings timings;
  private final URI uri;
  private final BooleanSupplier venueHours;
  private final HttpClient http;
  protected final ScheduledExecutorService exec;

  private volatile ConnectionState state = ConnectionState.CLOSED;
  private volatile Instant lastMessageAt;
  private volatile Instant liveSince;
  private volatile String detail;
  private volatile boolean closed;
  private volatile WebSocket socket;
  private long generation;
  private Duration backoff;
  private ScheduledFuture<?> authDeadline;
  private ScheduledFuture<?> reconnect;
  private final StringBuilder partial = new StringBuilder();

  /** Desired symbols (full state); a subclass diffs them against {@link #confirmed}. */
  protected volatile Set<String> desiredTrades = Set.of();
  protected volatile Set<String> desiredQuotes = Set.of();
  protected volatile Set<String> desiredBars = Set.of();
  /** Symbols the provider confirmed (or that were sent, when it does not confirm), with the time they started. */
  protected final Map<String, Instant> confirmed = new ConcurrentHashMap<>();

  protected WebSocketStream(String name, URI uri, TimeSource time, Timings timings, BooleanSupplier venueHours) {
    this.name = name;
    this.uri = uri;
    this.time = time;
    this.timings = timings;
    this.venueHours = venueHours;
    this.backoff = timings.initialBackoff();
    this.exec = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon(true)
        .name(name + "-stream").unstarted(r));
    this.http = HttpClient.newBuilder().connectTimeout(timings.connectTimeout())
        .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
  }

  /** Starts connecting (non-blocking). */
  public void start() {
    exec.execute(this::connect);
    exec.scheduleWithFixedDelay(this::watchdog, 1, 1, TimeUnit.SECONDS);
  }

  // ------------------------------------------------------------------------------------------- protocol hooks

  /** The socket opened; send authentication if the protocol has it (else call {@link #markLive}). */
  protected abstract void onConnected();

  /** One complete text message. Runs on the scheduler thread. */
  protected abstract void onMessage(String text);

  /** Sends unsubscribes first, then subscribes, so the total never exceeds the limit. */
  protected abstract void sendSubscriptionDiff();

  /** Provider-specific cleanup when a connection ends. */
  protected void onDisconnected() {
    confirmed.clear();
  }

  // ------------------------------------------------------------------------------------------- MarketStream

  @Override
  public void setDesired(Set<String> trades, Set<String> quotes, Set<String> bars) {
    Set<String> t = Set.copyOf(trades);
    Set<String> q = Set.copyOf(quotes);
    Set<String> b = Set.copyOf(bars);
    if (t.equals(desiredTrades) && q.equals(desiredQuotes) && b.equals(desiredBars)) {
      return;
    }
    desiredTrades = t;
    desiredQuotes = q;
    desiredBars = b;
    exec.execute(() -> {
      if (state == ConnectionState.LIVE || state == ConnectionState.LIMITED) {
        sendSubscriptionDiff();
      }
    });
  }

  @Override
  public ConnectionState state() {
    return state;
  }

  @Override
  public Instant lastMessageAt() {
    return lastMessageAt;
  }

  @Override
  public Set<String> subscribedSince(Instant t) {
    Instant live = liveSince;
    if (live == null || (state != ConnectionState.LIVE && state != ConnectionState.LIMITED)) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    confirmed.forEach((sym, since) -> {
      if (!since.isAfter(t)) {
        out.add(sym);
      }
    });
    return out;
  }

  @Override
  public Set<String> subscribed() {
    return Set.copyOf(confirmed.keySet());
  }

  /** Current subscriptions with the instant each began (the budget manager's hysteresis). */
  public Map<String, Instant> subscriptionTimes() {
    return Map.copyOf(confirmed);
  }

  public URI uri() {
    return uri;
  }

  @Override
  public String detail() {
    return detail;
  }

  @Override
  public void close() {
    closed = true;
    exec.execute(() -> {
      state = ConnectionState.CLOSED;
      WebSocket ws = socket;
      socket = null;
      if (ws != null) {
        try {
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").orTimeout(2, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
          ws.abort();
        }
      }
    });
    exec.shutdown();
    try {
      exec.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ------------------------------------------------------------------------------------------- helpers for subclasses

  protected void send(String text) {
    WebSocket ws = socket;
    if (ws != null) {
      ws.sendText(text, true);
    }
  }

  /** Authenticated (or no auth needed): subscriptions can be sent. */
  protected void markLive() {
    cancel(authDeadline);
    state = ConnectionState.LIVE;
    liveSince = time.now();
    backoff = timings.initialBackoff();
    detail = null;
    log.info("{} stream live ({})", name, uri.getHost());
    sendSubscriptionDiff();
  }

  protected void markLimited(String why) {
    state = ConnectionState.LIMITED;
    detail = why;
  }

  /** Drops the connection and reconnects after {@code wait} (null = the current exponential backoff). */
  protected void fail(String why, Duration wait) {
    detail = why;
    log.warn("{} stream: {}", name, why);
    WebSocket ws = socket;
    socket = null;
    generation++;
    if (ws != null) {
      ws.abort();
    }
    onDisconnected();
    scheduleReconnect(wait);
  }

  protected boolean isLive() {
    return state == ConnectionState.LIVE || state == ConnectionState.LIMITED;
  }

  // ------------------------------------------------------------------------------------------- connection

  private void connect() {
    if (closed) {
      return;
    }
    state = ConnectionState.CONNECTING;
    long gen = ++generation;
    partial.setLength(0);
    http.newWebSocketBuilder().connectTimeout(timings.connectTimeout()).buildAsync(uri, new Listener(gen))
        .whenComplete((ws, err) -> exec.execute(() -> {
          if (gen != generation || closed) {
            if (ws != null) {
              ws.abort();
            }
            return;
          }
          if (err != null) {
            fail("connect failed: " + rootMessage(err), null);
            return;
          }
          socket = ws;
          lastMessageAt = time.now();
          authDeadline = exec.schedule(() -> {
            if (gen == generation && state == ConnectionState.CONNECTING) {
              fail("not authenticated within " + timings.authTimeout().toSeconds() + " s", null);
            }
          }, timings.authTimeout().toMillis(), TimeUnit.MILLISECONDS);
          onConnected();
        }));
  }

  private void scheduleReconnect(Duration wait) {
    if (closed) {
      state = ConnectionState.CLOSED;
      return;
    }
    cancel(authDeadline);
    cancel(reconnect);
    Duration d = wait != null ? wait : backoff;
    if (wait == null) {
      Duration next = backoff.multipliedBy(2);
      backoff = next.compareTo(timings.maxBackoff()) > 0 ? timings.maxBackoff() : next;
    }
    state = ConnectionState.BACKOFF;
    liveSince = null;
    reconnect = exec.schedule(this::connect, d.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void watchdog() {
    try {
      if (state == ConnectionState.LIVE || state == ConnectionState.LIMITED) {
        Instant last = lastMessageAt;
        if (last != null && Duration.between(last, time.now()).compareTo(timings.watchdogSilence()) > 0
            && venueHours.getAsBoolean()) {
          fail("no message for " + timings.watchdogSilence().toSeconds() + " s during venue hours", Duration.ZERO);
        }
      }
    } catch (RuntimeException e) {
      log.debug("{} watchdog: {}", name, e.toString());
    }
  }

  private static void cancel(ScheduledFuture<?> f) {
    if (f != null) {
      f.cancel(false);
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
  }

  private final class Listener implements WebSocket.Listener {
    private final long gen;

    Listener(long gen) {
      this.gen = gen;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      // Messages (the server's greeting) can arrive before buildAsync completes: make the socket usable first, on
      // the same single-threaded executor that will process them, so a reply is never sent to a null socket.
      exec.execute(() -> {
        if (gen == generation && !closed) {
          socket = webSocket;
        }
      });
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      String chunk = data.toString();
      exec.execute(() -> {
        if (gen != generation) {
          return;
        }
        lastMessageAt = time.now();
        partial.append(chunk);
        if (last) {
          String msg = partial.toString();
          partial.setLength(0);
          try {
            onMessage(msg);
          } catch (RuntimeException e) {
            log.warn("{} stream: bad message ({}): {}", name, e.toString(),
                msg.length() > 300 ? msg.substring(0, 300) + "…" : msg);
          }
        }
      });
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
      exec.execute(() -> {
        if (gen == generation) {
          lastMessageAt = time.now();
        }
      });
      webSocket.request(1);
      return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      exec.execute(() -> {
        if (gen == generation && !closed) {
          fail("closed by server (" + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason) + ")",
              null);
        }
      });
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      exec.execute(() -> {
        if (gen == generation && !closed) {
          fail("socket error: " + rootMessage(error), null);
        }
      });
    }
  }
}
