package com.stocks.trading.engine;

import com.stocks.trading.config.EngineProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Simulated order latency (§11.3, D9): {@code 250 ms + jitter}, {@code jitter = 100 + (hash64(ENGINE_SEED, order,
 * activationNo) mod 401)} ms, i.e. 100–500 ms. Seeded, so a replay is deterministic, and secret in prod, so it cannot
 * be gamed. {@code activationNo} is 0 at acceptance and increments on each resume.
 */
@Component
public class LatencyModel {

  private final EngineProperties props;

  public LatencyModel(EngineProperties props) {
    this.props = props;
  }

  public Duration latency(String orderPublicId, int activationNo) {
    long min = props.latencyJitterMin().toMillis();
    long range = props.latencyJitterMax().toMillis() - min + 1;
    long jitter = min + Math.floorMod(hash64(props.seed(), orderPublicId, activationNo), range);
    return props.latencyBase().plusMillis(jitter);
  }

  static long hash64(String seed, String orderPublicId, int activationNo) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(seed.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(orderPublicId.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(ByteBuffer.allocate(4).putInt(activationNo).array());
      return ByteBuffer.wrap(md.digest()).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
