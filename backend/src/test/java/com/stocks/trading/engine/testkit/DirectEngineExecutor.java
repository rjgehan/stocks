package com.stocks.trading.engine.testkit;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.trading.EngineStatus;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.engine.EngineCore;
import com.stocks.trading.engine.EngineThread;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Runs the engine on the calling thread (§12.10): commands, the dirty-symbol drain and the sweep execute
 * synchronously, so a scenario test controls time ({@link MutableTimeSource}) and the market (scripted feed) exactly.
 * The Spring test context runs with {@code stocks.engine.auto-start=false}, so the production {@code EngineRunner}
 * also executes submitted commands on the caller's thread and never starts {@code engine-main}.
 */
public final class DirectEngineExecutor {

  private final EngineCore core;
  private final MutableTimeSource time;

  public DirectEngineExecutor(EngineCore core, MutableTimeSource time) {
    this.core = core;
    this.time = time;
  }

  public synchronized <R> CompletableFuture<R> submit(EngineCommand cmd) {
    try {
      return CompletableFuture.completedFuture(run(cmd));
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /** Executes a command and returns its result (or throws its {@code ApiException}). */
  public synchronized <R> R run(EngineCommand cmd) {
    return EngineThread.runAsEngine(() -> {
      bootstrap();
      return core.execute(cmd);
    });
  }

  /** One loop step: the dirty-symbol drain, then the sweep (§12.2). */
  public synchronized void step() {
    EngineThread.runAsEngine(() -> {
      bootstrap();
      core.evaluateDirty();
      core.sweep();
      return null;
    });
  }

  /** Moves the clock, then runs one loop step. */
  public void advance(Duration d) {
    time.advance(d);
    step();
  }

  /** A simulated process restart: the engine forgets everything in memory and reloads from the database (§26). */
  public synchronized void restart() {
    EngineThread.runAsEngine(() -> {
      core.restart();
      return null;
    });
  }

  public EngineStatus status() {
    return core.status(0);
  }

  private void bootstrap() {
    if (!core.isBootstrapped()) {
      core.bootstrap();
    }
  }
}
