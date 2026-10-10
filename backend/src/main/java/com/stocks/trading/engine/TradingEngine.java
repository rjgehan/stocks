package com.stocks.trading.engine;

import com.stocks.contract.trading.EngineStatus;
import java.util.concurrent.CompletableFuture;

/**
 * The single-writer engine (§12.1), internal to WS3; other workstreams use {@code EngineControl} and the trading
 * contract services. {@link EngineRunner} runs commands on {@code engine-main}; tests use {@code DirectEngineExecutor},
 * which runs them on the calling thread (§12.10).
 */
public interface TradingEngine {

  /**
   * Queues a command. The future completes with the command's result after commit, or exceptionally with an
   * {@code ApiException} ({@code ENGINE_BUSY} when the queue is full, {@code ENGINE_HALTED}, or the command's own
   * error).
   */
  <R> CompletableFuture<R> submit(EngineCommand cmd);

  EngineStatus status();
}
