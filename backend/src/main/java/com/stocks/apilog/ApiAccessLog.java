package com.stocks.apilog;

import com.stocks.apilog.ApiAccessLogRepository.Delta;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.security.PlayerPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The API access log's aggregator (§17.10): calls of API keys are counted in memory per <b>(UTC hour, key, channel,
 * endpoint, outcome)</b> (calls, total and maximum milliseconds, first and last call, last IP and client) and written
 * by one flusher thread ({@code api-log-flush}) every {@code stocks.api-log.flush-interval}, at shutdown and before
 * every read ({@link #flush}). At most {@code stocks.api-log.max-pending-buckets} buckets wait; calls for a new bucket
 * beyond that are dropped and counted in one WARN per flush. {@link #record} never blocks on the database and never
 * throws. Not a job: {@code stocks.jobs.enabled=false} does not stop it.
 */
@Component
public class ApiAccessLog implements ApiAccessRecorder, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ApiAccessLog.class);

  public static final int ENDPOINT_MAX = 120;
  public static final int OUTCOME_MAX = 48;
  public static final int IP_MAX = 45;
  public static final int CLIENT_MAX = 120;

  record BucketKey(Instant hourStart, long playerId, long apiKeyId, Channel channel, String endpoint,
                   String outcome) {}

  /** Mutable counts of one bucket; only touched inside {@code ConcurrentHashMap.compute} for its key. */
  static final class Bucket {
    int calls;
    long totalMs;
    int maxMs;
    Instant firstAt;
    Instant lastAt;
    String lastIp;
    String lastClient;

    void add(Instant at, long ms, String ip, String client) {
      calls++;
      totalMs += ms;
      maxMs = (int) Math.max(maxMs, Math.min(ms, Integer.MAX_VALUE));
      if (firstAt == null || at.isBefore(firstAt)) {
        firstAt = at;
      }
      if (lastAt == null || !at.isBefore(lastAt)) {
        lastAt = at;
        lastIp = ip;
        lastClient = client;
      }
    }
  }

  private final ApiAccessLogRepository repository;
  private final TimeSource time;
  private final ApiAccessLogProperties properties;
  private final Map<BucketKey, Bucket> pending = new ConcurrentHashMap<>();
  private final Map<Long, String> keyRefs = new ConcurrentHashMap<>();
  private final AtomicLong dropped = new AtomicLong();
  private final Object flushLock = new Object();
  private volatile boolean running;
  private volatile Thread thread;

  public ApiAccessLog(ApiAccessLogRepository repository, TimeSource time, ApiAccessLogProperties properties) {
    this.repository = repository;
    this.time = time;
    this.properties = properties == null ? ApiAccessLogProperties.defaults() : properties;
  }

  public boolean enabled() {
    return properties.enabled();
  }

  // ------------------------------------------------------------------------------------------- record

  @Override
  public void record(PlayerPrincipal caller, Channel channel, String endpoint, String outcome, long durationMs,
                     String ip, String client) {
    if (!properties.enabled() || caller == null || caller.authMethod() != AuthMethod.API_KEY
        || caller.apiKeyId() == null || channel == null || endpoint == null || outcome == null) {
      return;
    }
    try {
      Instant now = time.now();
      BucketKey key = new BucketKey(now.truncatedTo(ChronoUnit.HOURS), caller.playerId(), caller.apiKeyId(), channel,
          Text.clip(endpoint, ENDPOINT_MAX), Text.clip(outcome, OUTCOME_MAX));
      if (!pending.containsKey(key) && pending.size() >= properties.maxPendingBuckets()) {
        dropped.incrementAndGet();
        return;
      }
      long ms = Math.max(0, durationMs);
      String cleanIp = Text.trimToNull(Text.clip(ip, IP_MAX));
      String cleanClient = Text.trimToNull(Text.clip(client, CLIENT_MAX));
      pending.compute(key, (k, b) -> {
        Bucket bucket = b == null ? new Bucket() : b;
        bucket.add(now, ms, cleanIp, cleanClient);
        return bucket;
      });
    } catch (RuntimeException e) {
      log.debug("API access log record failed: {}", e.toString());
    }
  }

  /** Buckets waiting to be written (tests). */
  int pendingBuckets() {
    return pending.size();
  }

  // ------------------------------------------------------------------------------------------- flush

  /** Writes every pending bucket now; returns how many were written. Safe to call from any thread. */
  public int flush() {
    synchronized (flushLock) {
      int written = 0;
      for (BucketKey key : List.copyOf(pending.keySet())) {
        AtomicReference<Bucket> taken = new AtomicReference<>();
        pending.computeIfPresent(key, (k, b) -> {
          taken.set(b);
          return null;
        });
        Bucket b = taken.get();
        if (b == null || b.calls == 0) {
          continue;
        }
        Optional<String> keyRef = keyRef(key.apiKeyId());
        if (keyRef.isEmpty()) {
          continue;                                   // the key row is gone: nothing to attach the calls to
        }
        try {
          repository.add(new Delta(key.hourStart(), key.playerId(), key.apiKeyId(), keyRef.get(), key.channel(),
              key.endpoint(), key.outcome(), b.calls, b.totalMs, b.maxMs, b.firstAt, b.lastAt, b.lastIp,
              b.lastClient));
          written++;
        } catch (RuntimeException e) {
          log.warn("API access log: could not write a bucket of key {} ({} calls): {}", keyRef.get(), b.calls,
              e.toString());
        }
      }
      long lost = dropped.getAndSet(0);
      if (lost > 0) {
        log.warn("API access log: {} calls dropped (more than {} buckets pending)", lost,
            properties.maxPendingBuckets());
      }
      return written;
    }
  }

  private Optional<String> keyRef(long apiKeyId) {
    String cached = keyRefs.get(apiKeyId);
    if (cached != null) {
      return Optional.of(cached);
    }
    try {
      Optional<String> ref = repository.keyRef(apiKeyId);
      ref.ifPresent(r -> keyRefs.put(apiKeyId, r));
      return ref;
    } catch (RuntimeException e) {
      log.debug("API access log: key {} lookup failed: {}", apiKeyId, e.toString());
      return Optional.empty();
    }
  }

  // ------------------------------------------------------------------------------------------- thread

  @Override
  public void start() {
    if (running || !properties.enabled()) {
      return;
    }
    running = true;
    thread = Thread.ofPlatform().daemon().name("api-log-flush").start(this::loop);
  }

  @Override
  public void stop() {
    running = false;
    Thread t = thread;
    if (t != null) {
      LockSupport.unpark(t);
      try {
        t.join(TimeUnit.SECONDS.toMillis(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    thread = null;
    try {
      flush();                                        // at shutdown: nothing counted is lost
    } catch (RuntimeException e) {
      log.warn("API access log: final flush failed: {}", e.toString());
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * Stops after the web server (Boot's start/stop phase is {@code DEFAULT_PHASE - 2048}), so the last requests are
   * counted, and before the data source closes (beans are destroyed after every lifecycle stopped).
   */
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE - 4096;
  }

  private void loop() {
    long every = Math.max(1, properties.flushInterval().toNanos());
    while (running) {
      LockSupport.parkNanos(every);
      if (!running) {
        break;
      }
      try {
        flush();
      } catch (RuntimeException e) {
        log.warn("API access log flush failed: {}", e.toString());
      }
    }
  }
}
