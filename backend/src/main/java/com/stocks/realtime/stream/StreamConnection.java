package com.stocks.realtime.stream;

import com.stocks.contract.realtime.dto.StreamByeDto;
import com.stocks.contract.realtime.dto.StreamTopic;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One open SSE stream (§19.1): a bounded queue of ready frames and one sender (a virtual thread) that writes them,
 * coalesces quotes per symbol (at most one per {@code quoteInterval}, always the latest), writes a {@code : hb}
 * comment after {@code heartbeat} of silence, and ends the stream with a {@code bye} when the hub asks
 * ({@code REVOKED}, {@code SHUTDOWN}), when it reaches {@code maxAge} or when the queue overflows
 * ({@code SLOW_CONSUMER}). Quotes never use the queue, so they cannot fill it.
 */
public final class StreamConnection {

  private static final Logger log = LoggerFactory.getLogger(StreamConnection.class);

  /** How often the sender wakes without frames (quotes due, heartbeat, max age). */
  static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private final String id;
  private final long playerId;
  private final Long apiKeyId;
  private final Set<StreamTopic> topics;
  private final List<String> symbols;
  private final StreamSink sink;
  private final BlockingQueue<String> queue;
  private final Set<String> dirtyQuotes = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> lastQuoteNanos = new HashMap<>();
  private final long quoteIntervalNanos;
  private final long heartbeatNanos;
  private final long maxAgeNanos;
  private final long openedNanos;
  private final LongSupplier nanos;
  private final Function<String, String> quoteFrame;
  private final Supplier<String> heartbeatFrame;
  private final Function<StreamByeDto.Reason, String> byeFrame;
  private volatile StreamByeDto.Reason closing;
  private volatile boolean done;
  private volatile Thread sender;
  private Runnable onClosed = () -> { };

  StreamConnection(String id, long playerId, Long apiKeyId, Set<StreamTopic> topics, List<String> symbols,
                   StreamSink sink, StreamProperties properties, LongSupplier nanos,
                   Function<String, String> quoteFrame, Supplier<String> heartbeatFrame,
                   Function<StreamByeDto.Reason, String> byeFrame) {
    this.id = id;
    this.playerId = playerId;
    this.apiKeyId = apiKeyId;
    this.topics = Set.copyOf(topics);
    this.symbols = List.copyOf(symbols);
    this.sink = sink;
    this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.queueCapacity()));
    this.quoteIntervalNanos = properties.quoteInterval().toNanos();
    this.heartbeatNanos = Math.max(TICK_NANOS, properties.heartbeat().toNanos());
    this.maxAgeNanos = properties.maxAge().toNanos();
    this.nanos = nanos;
    this.openedNanos = nanos.getAsLong();
    this.quoteFrame = quoteFrame;
    this.heartbeatFrame = heartbeatFrame;
    this.byeFrame = byeFrame;
  }

  public String id() {
    return id;
  }

  public long playerId() {
    return playerId;
  }

  public Long apiKeyId() {
    return apiKeyId;
  }

  public Set<StreamTopic> topics() {
    return topics;
  }

  public List<String> symbols() {
    return symbols;
  }

  public boolean wants(StreamTopic topic) {
    return topics.contains(topic);
  }

  public boolean isDone() {
    return done;
  }

  /** Called once when the stream has ended (the hub unregisters it). */
  void onClosed(Runnable callback) {
    this.onClosed = callback;
  }

  /** Queues a ready frame; a full queue closes the stream with {@code SLOW_CONSUMER}. */
  boolean offer(String frame) {
    if (done || closing != null) {
      return false;
    }
    if (!queue.offer(frame)) {
      close(StreamByeDto.Reason.SLOW_CONSUMER);
      return false;
    }
    return true;
  }

  /** A quote of {@code symbol} changed: it is sent when its interval allows. */
  void quoteChanged(String symbol) {
    if (!done && topics.contains(StreamTopic.QUOTES) && symbols.contains(symbol)) {
      dirtyQuotes.add(symbol);
    }
  }

  /**
   * Asks the sender to end the stream with {@code bye {reason}} (the first reason wins); it notices within one tick.
   * No interrupt: it could abort a write in progress and lose the {@code bye}.
   */
  public void close(StreamByeDto.Reason reason) {
    if (closing == null) {
      closing = reason;
    }
  }

  /** Waits up to {@code millis} for the sender to finish (shutdown). */
  void await(long millis) {
    Thread t = sender;
    if (t != null) {
      try {
        t.join(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Starts the sender on a virtual thread. */
  void start() {
    sender = Thread.ofVirtual().name("sse-" + id).start(this::run);
  }

  /** The sender loop (tests may run it on their own thread). */
  void run() {
    long lastWrite = nanos.getAsLong();
    try {
      while (closing == null) {
        String frame;
        try {
          frame = queue.poll(TICK_NANOS, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        long now = nanos.getAsLong();
        if (closing != null) {
          break;
        }
        while (frame != null) {
          sink.send(frame);
          lastWrite = now;
          frame = queue.poll();
        }
        if (sendDueQuotes(now)) {
          lastWrite = now;
        }
        if (maxAgeNanos > 0 && now - openedNanos >= maxAgeNanos) {
          close(StreamByeDto.Reason.MAX_AGE);
          break;
        }
        if (now - lastWrite >= heartbeatNanos) {
          sink.send(heartbeatFrame.get());
          lastWrite = now;
        }
      }
      StreamByeDto.Reason reason = closing == null ? StreamByeDto.Reason.SHUTDOWN : closing;
      if (reason != StreamByeDto.Reason.SLOW_CONSUMER) {
        // REVOKED, MAX_AGE, SHUTDOWN: what is already queued (e.g. the event that preceded a revocation) goes first
        String frame;
        while ((frame = queue.poll()) != null) {
          sink.send(frame);
        }
      }
      queue.clear();
      sink.send(byeFrame.apply(reason));
    } catch (IOException | RuntimeException e) {
      log.debug("SSE stream {} ended: {}", id, e.toString());
    } finally {
      done = true;
      queue.clear();
      try {
        sink.close();
      } catch (RuntimeException e) {
        log.debug("SSE stream {} close: {}", id, e.toString());
      }
      onClosed.run();
    }
  }

  /** Sends every dirty quote whose interval has passed; true if anything was written. */
  private boolean sendDueQuotes(long now) throws IOException {
    if (dirtyQuotes.isEmpty()) {
      return false;
    }
    boolean wrote = false;
    for (String symbol : List.copyOf(dirtyQuotes)) {
      Long last = lastQuoteNanos.get(symbol);
      if (last != null && now - last < quoteIntervalNanos) {
        continue;
      }
      dirtyQuotes.remove(symbol);
      String frame = quoteFrame.apply(symbol);
      if (frame != null) {
        sink.send(frame);
        wrote = true;
      }
      lastQuoteNanos.put(symbol, now);
    }
    return wrote;
  }

  /** Frames waiting (tests). */
  int queued() {
    return queue.size();
  }
}
