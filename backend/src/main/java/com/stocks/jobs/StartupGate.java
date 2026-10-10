package com.stocks.jobs;

import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The boot barrier of §12.9. Everything that must happen before the engine accepts commands runs before
 * {@link #start()} (which Spring calls after every singleton is ready, and before the web server starts accepting
 * requests, because {@link #PHASE} is lower than the server's lifecycle phase):
 * <ol>
 *   <li>{@code SingleInstanceGuard} took the advisory lock or H2 lease (a {@code SmartInitializingSingleton});</li>
 *   <li>WS2 loads the calendar and assets, WS3 runs {@code InvariantChecker} (their own initialization);</li>
 *   <li>{@link #start()}: {@link CatchUpRunner} replays missed jobs and transitions, in time order;</li>
 *   <li>the gate opens ({@link #whenOpen()} completes): WS3's {@code EngineBootstrap} loads {@code OpenOrderIndex}
 *       and opens the engine queue;</li>
 *   <li>{@link JobScheduler} is armed from the catch-up instant onward.</li>
 * </ol>
 * On shutdown the scheduler stops after the web server (lower phases stop last).
 */
@Component
public class StartupGate implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(StartupGate.class);

  /** Starts early, stops late: below the web server's lifecycle phases. */
  public static final int PHASE = 0;

  private final CatchUpRunner catchUp;
  private final JobScheduler scheduler;
  private final TimeSource time;
  private final StocksProperties properties;
  private final CompletableFuture<Instant> opened = new CompletableFuture<>();
  private volatile boolean running;

  public StartupGate(CatchUpRunner catchUp, JobScheduler scheduler, TimeSource time, StocksProperties properties) {
    this.catchUp = catchUp;
    this.scheduler = scheduler;
    this.time = time;
    this.properties = properties;
  }

  @Override
  public void start() {
    Instant upTo = time.now();
    catchUp.runAtBoot(upTo); // no-op when stocks.jobs.enabled=false
    opened.complete(time.now());
    log.debug("StartupGate open (jobs enabled: {})", properties.jobs().enabled());
    scheduler.start(upTo);
    running = true;
  }

  @Override
  public void stop() {
    scheduler.stop();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  /** True once catch-up finished; the engine may accept commands. */
  public boolean isOpen() {
    return opened.isDone();
  }

  /** Completes with the opening instant; WS3 opens the engine queue on it. */
  public CompletableFuture<Instant> whenOpen() {
    return opened.copy();
  }

  /** Blocks until the gate opens. */
  public Instant awaitOpen(Duration timeout) throws InterruptedException, TimeoutException {
    try {
      return opened.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    }
  }
}
