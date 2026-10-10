package com.stocks.trading.engine;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.trading.EngineStatus;
import com.stocks.trading.config.EngineProperties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drives {@link EngineCore} from one platform thread, {@code engine-main} (D2, §12.2): a bounded queue polled every
 * sweep interval, the dirty-symbol drain after each command, and the 250 ms sweep. It starts before
 * {@code StartupGate} (lower lifecycle phase), so boot catch-up can submit transitions, and stops after it, draining
 * the queue for up to 10 s (§12.9).
 *
 * <p>With {@code stocks.engine.auto-start=false} there is no thread: {@link #submit} runs the command synchronously
 * on the calling thread, one at a time (the deterministic mode of §12.10, used by tests). It is also the engine's
 * {@link MarketUpdateListener}: a market update only marks the symbol dirty (never blocks).
 */
@Component
public class EngineRunner implements TradingEngine, MarketUpdateListener, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EngineRunner.class);

  /** Before {@code StartupGate.PHASE} (0). */
  public static final int PHASE = -100;
  private static final int BATCH = 100;
  private static final long DRAIN_MILLIS = 10_000;

  private record Envelope(EngineCommand cmd, CompletableFuture<Object> future) {}

  private final EngineCore core;
  private final EngineProperties props;
  private final BlockingQueue<Envelope> queue;
  private final Object directLock = new Object();
  private volatile boolean running;
  private volatile boolean accepting;
  private Thread thread;

  public EngineRunner(EngineCore core, EngineProperties props) {
    this.core = core;
    this.props = props;
    this.queue = new LinkedBlockingQueue<>(props.queueCapacity());
  }

  // ------------------------------------------------------------------------------------------- TradingEngine

  @Override
  @SuppressWarnings("unchecked")
  public <R> CompletableFuture<R> submit(EngineCommand cmd) {
    if (!props.autoStart()) {
      return (CompletableFuture<R>) direct(cmd);
    }
    CompletableFuture<Object> future = new CompletableFuture<>();
    if (!accepting && running) {
      future.completeExceptionally(busy("The engine is shutting down."));
      return (CompletableFuture<R>) future;
    }
    if (!queue.offer(new Envelope(cmd, future))) {
      future.completeExceptionally(busy("The engine queue is full."));
    }
    return (CompletableFuture<R>) future;
  }

  private CompletableFuture<Object> direct(EngineCommand cmd) {
    synchronized (directLock) {
      try {
        return CompletableFuture.completedFuture(EngineThread.runAsEngine(() -> {
          if (!core.isBootstrapped()) {
            core.bootstrap();
          }
          return core.execute(cmd);
        }));
      } catch (RuntimeException e) {
        return CompletableFuture.failedFuture(e);
      }
    }
  }

  @Override
  public EngineStatus status() {
    return core.status(queue.size());
  }

  @Override
  public void onMarketUpdate(String symbol) {
    core.markDirty(symbol);
  }

  private static ApiException busy(String detail) {
    return ApiException.of(ErrorCode.ENGINE_BUSY, detail).withRetryAfter(1);
  }

  // ------------------------------------------------------------------------------------------- lifecycle

  @Override
  public void start() {
    running = true;
    accepting = true;
    if (!props.autoStart()) {
      return;
    }
    thread = new Thread(this::loop, EngineThread.NAME);
    thread.setDaemon(false);
    thread.start();
  }

  @Override
  public void stop() {
    accepting = false;
    if (thread != null) {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MILLIS);
      while (!queue.isEmpty() && System.nanoTime() < deadline) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      running = false;
      try {
        thread.join(DRAIN_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (thread.isAlive()) {
        thread.interrupt();
      }
    }
    running = false;
    Envelope left;
    while ((left = queue.poll()) != null) {
      left.future().completeExceptionally(busy("The engine stopped."));
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  private void loop() {
    EngineThread.markCurrentThread();
    try {
      core.bootstrap();
    } catch (RuntimeException e) {
      log.error("Engine bootstrap failed", e);
    }
    long interval = props.sweepInterval().toNanos();
    long nextSweep = System.nanoTime();
    while (running) {
      try {
        Envelope env = queue.poll(props.sweepInterval().toMillis(), TimeUnit.MILLISECONDS);
        int n = 0;
        while (env != null) {
          run(env);
          env = ++n < BATCH ? queue.poll() : null;
        }
        core.evaluateDirty();
        if (System.nanoTime() - nextSweep >= 0) {
          core.sweep();
          nextSweep = System.nanoTime() + interval;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        log.error("Engine loop step failed", e);
      }
    }
  }

  private void run(Envelope env) {
    try {
      env.future().complete(core.execute(env.cmd()));
    } catch (RuntimeException e) {
      env.future().completeExceptionally(e);
    }
  }
}
