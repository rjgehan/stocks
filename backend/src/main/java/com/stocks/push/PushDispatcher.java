package com.stocks.push;

import com.stocks.common.time.TimeSource;
import com.stocks.push.PushSender.Outcome;
import com.stocks.push.PushSubscriptions.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers Web Push messages off every request and engine thread (§19.2): a bounded pool of
 * {@code stocks.push.threads} virtual threads with a queue of {@code stocks.push.queue-capacity} (overflow is dropped
 * and logged), plus one scheduler thread for retries. 429, 5xx and I/O errors are retried up to
 * {@code stocks.push.max-attempts} times after 2 s and 10 s (or the push service's {@code Retry-After}, at most 5 min).
 * 404/410 deletes the subscription; {@value #MAX_FAILURES} consecutive other failures delete it too. A delivery whose
 * subscription moved to another player meanwhile is skipped: a message is only ever sent to its own player's device.
 */
@Component
public class PushDispatcher implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

  static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));
  static final int MAX_FAILURES = 10;

  /** The final state of one delivery. */
  public enum Delivery { DELIVERED, FAILED, REMOVED, SKIPPED, DROPPED }

  private final PushSubscriptions subscriptions;
  private final PushSender sender;
  private final JsonMapper mapper;
  private final TimeSource time;
  private final PushProperties props;
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService retries;
  private final AtomicLong dropped = new AtomicLong();

  public PushDispatcher(PushSubscriptions subscriptions, PushSender sender, JsonMapper mapper, TimeSource time,
                        PushProperties props) {
    this.subscriptions = subscriptions;
    this.sender = sender;
    this.mapper = mapper;
    this.time = time;
    this.props = props;
    int threads = Math.max(1, props.threads());
    this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(Math.max(1, props.queueCapacity())),
        Thread.ofVirtual().name("push-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    this.retries = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("push-retry")
        .factory());
  }

  /** Runs {@code task} on the delivery pool; false (and logged) when the queue is full. */
  public boolean execute(Runnable task) {
    try {
      workers.execute(() -> {
        try {
          task.run();
        } catch (RuntimeException e) {
          log.warn("Web Push task failed: {}", e.toString());
        }
      });
      return true;
    } catch (RejectedExecutionException e) {
      long n = dropped.incrementAndGet();
      if (n == 1 || n % 100 == 0) {
        log.warn("Web Push queue full: {} message(s) dropped so far", n);
      }
      return false;
    }
  }

  /** Delivers {@code message} to {@code subscription}; the future completes with the final outcome (after retries). */
  public CompletableFuture<Delivery> deliver(Subscription subscription, PushMessage message) {
    CompletableFuture<Delivery> done = new CompletableFuture<>();
    byte[] payload;
    try {
      payload = message.payload(mapper);
    } catch (RuntimeException e) {
      log.warn("Web Push payload could not be built: {}", e.toString());
      done.complete(Delivery.FAILED);
      return done;
    }
    Job job = new Job(subscription.id(), subscription.playerId(), message, payload, done);
    if (!execute(() -> attempt(job, 1))) {
      done.complete(Delivery.DROPPED);
    }
    return done;
  }

  private record Job(long subscriptionId, long playerId, PushMessage message, byte[] payload,
                     CompletableFuture<Delivery> done) {}

  void attempt(Job job, int attempt) {
    Optional<Subscription> current = subscriptions.byId(job.subscriptionId());
    if (current.isEmpty() || current.get().playerId() != job.playerId()) {
      job.done().complete(Delivery.SKIPPED);               // removed, or the device changed hands
      return;
    }
    Subscription sub = current.get();
    Outcome outcome = sender.send(sub, job.message(), job.payload());
    switch (outcome.result()) {
      case DELIVERED -> {
        subscriptions.recordSuccess(sub.id(), time.now());
        job.done().complete(Delivery.DELIVERED);
      }
      case GONE -> {
        subscriptions.delete(sub.id());
        log.info("Removed push device {} of player {} (HTTP {})", sub.publicId(), sub.playerId(), outcome.status());
        job.done().complete(Delivery.REMOVED);
      }
      case RETRY -> {
        if (attempt < Math.max(1, props.maxAttempts())) {
          Duration delay = outcome.retryAfter() != null ? outcome.retryAfter()
              : BACKOFF.get(Math.min(attempt - 1, BACKOFF.size() - 1));
          try {
            retries.schedule(() -> {
              if (!execute(() -> attempt(job, attempt + 1))) {
                job.done().complete(Delivery.DROPPED);
              }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
          } catch (RejectedExecutionException e) {
            job.done().complete(Delivery.DROPPED);            // shutting down
          }
        } else {
          failed(sub, job);
        }
      }
      case FAILED -> failed(sub, job);
      default -> job.done().complete(Delivery.FAILED);
    }
  }

  private void failed(Subscription sub, Job job) {
    int failures = subscriptions.recordFailure(sub.id(), time.now());
    if (failures >= MAX_FAILURES) {
      subscriptions.delete(sub.id());
      log.info("Removed push device {} of player {} after {} consecutive failures", sub.publicId(), sub.playerId(),
          failures);
      job.done().complete(Delivery.REMOVED);
    } else {
      job.done().complete(Delivery.FAILED);
    }
  }

  /** Deliveries dropped because the queue was full (system status, tests). */
  public long droppedCount() {
    return dropped.get();
  }

  @Override
  public void destroy() {
    retries.shutdownNow();
    workers.shutdown();
    try {
      if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
        workers.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      workers.shutdownNow();
    }
  }
}
