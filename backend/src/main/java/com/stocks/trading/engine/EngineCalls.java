package com.stocks.trading.engine;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.trading.config.EngineProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * How services call the engine (§4 rule 5): submit, then wait at most {@code stocks.engine.submit-timeout}.
 * A timeout answers {@code ENGINE_TIMEOUT} (503); the command may still run, so clients retry with the same
 * {@code clientOrderId} or {@code Idempotency-Key}.
 */
@Component
public class EngineCalls {

  private final TradingEngine engine;
  private final EngineProperties props;

  public EngineCalls(TradingEngine engine, EngineProperties props) {
    this.engine = engine;
    this.props = props;
  }

  public <R> R call(EngineCommand cmd) {
    CompletableFuture<R> future = engine.submit(cmd);
    try {
      return future.get(props.submitTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw ApiException.of(ErrorCode.ENGINE_TIMEOUT, "The trading engine did not answer in time.")
          .withHint("Retry with the same clientOrderId / Idempotency-Key; the request may still complete.")
          .withRetryAfter(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.of(ErrorCode.ENGINE_TIMEOUT, "Interrupted while waiting for the engine.");
    } catch (ExecutionException e) {
      throw unwrap(e.getCause());
    }
  }

  static RuntimeException unwrap(Throwable t) {
    Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    if (cause instanceof RuntimeException re) {
      return re;
    }
    return new ApiException(ErrorCode.INTERNAL_ERROR, null, cause);
  }

  public TradingEngine engine() {
    return engine;
  }
}
