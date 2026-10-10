package com.stocks.trading.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code stocks.engine} (§23.1, WS3): the single-writer engine (§12).
 *
 * @param seed            secret seed of the latency jitter (§11.3); {@code ENGINE_SEED} in prod
 * @param queueCapacity   bounded command queue; a full queue answers {@code ENGINE_BUSY} (§4 rule 5)
 * @param submitTimeout   how long a caller waits for its command; then {@code ENGINE_TIMEOUT}
 * @param sweepInterval   the sweep cadence and the queue poll timeout (§12.2)
 * @param latencyBase     fixed part of the simulated latency (250 ms)
 * @param latencyJitterMin seeded jitter lower bound (100 ms)
 * @param latencyJitterMax seeded jitter upper bound, inclusive (500 ms)
 * @param autoStart       run the {@code engine-main} thread; {@code false} only in deterministic tests that drive
 *                        the engine on the calling thread ({@code DirectEngineExecutor}, §12.10)
 */
@ConfigurationProperties("stocks.engine")
public record EngineProperties(String seed, Integer queueCapacity, Duration submitTimeout, Duration sweepInterval,
                               Duration latencyBase, Duration latencyJitterMin, Duration latencyJitterMax,
                               Boolean autoStart) {

  public EngineProperties {
    seed = seed == null || seed.isBlank() ? "dev-seed-change-me" : seed;
    queueCapacity = queueCapacity == null || queueCapacity < 1 ? 10_000 : queueCapacity;
    submitTimeout = submitTimeout == null ? Duration.ofSeconds(5) : submitTimeout;
    sweepInterval = sweepInterval == null ? Duration.ofMillis(250) : sweepInterval;
    latencyBase = latencyBase == null ? Duration.ofMillis(250) : latencyBase;
    latencyJitterMin = latencyJitterMin == null ? Duration.ofMillis(100) : latencyJitterMin;
    latencyJitterMax = latencyJitterMax == null ? Duration.ofMillis(500) : latencyJitterMax;
    if (latencyJitterMax.compareTo(latencyJitterMin) < 0) {
      throw new IllegalArgumentException("stocks.engine.latency-jitter-max < latency-jitter-min");
    }
    autoStart = autoStart == null || autoStart;
  }

  /** The defaults of §23.1. */
  public static EngineProperties defaults() {
    return new EngineProperties(null, null, null, null, null, null, null, null);
  }
}
