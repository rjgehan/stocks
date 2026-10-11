package com.stocks.realtime.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code stocks.stream} (§19.3, WS5): the SSE stream for API clients. Every key has a safe default, so production
 * needs no new configuration.
 *
 * @param enabled                 false answers {@code GET /api/v1/stream} with 404 {@code NOT_FOUND}
 * @param maxConnectionsPerPlayer open streams per player (beyond: 429 {@code RATE_LIMITED}, {@code Retry-After: 30})
 * @param maxConnections          open streams in total
 * @param heartbeat               a {@code : hb} comment after this much silence (well under Cloudflare's ~100 s)
 * @param maxAge                  a stream's lifetime; then {@code bye MAX_AGE} and the client reconnects
 * @param revalidateEvery         how often open streams re-check their key and player
 * @param bufferSize              resumable events kept for {@code Last-Event-ID}
 * @param bufferMaxAge            ... and for at most this long (also the reconnect window of private events)
 * @param quoteInterval           at most one {@code quote} per symbol per interval (coalesced to the latest)
 * @param queueCapacity           frames waiting per stream; a client that falls this far behind is closed
 * @param retryMs                 the {@code retry:} sent first (EventSource's reconnect delay)
 * @param maxSymbols              symbols per stream
 */
@ConfigurationProperties("stocks.stream")
public record StreamProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("3") int maxConnectionsPerPlayer,
                               @DefaultValue("200") int maxConnections,
                               @DefaultValue("15s") Duration heartbeat,
                               @DefaultValue("30m") Duration maxAge,
                               @DefaultValue("30s") Duration revalidateEvery,
                               @DefaultValue("2000") int bufferSize,
                               @DefaultValue("5m") Duration bufferMaxAge,
                               @DefaultValue("1s") Duration quoteInterval,
                               @DefaultValue("1000") int queueCapacity,
                               @DefaultValue("3000") int retryMs,
                               @DefaultValue("50") int maxSymbols) {

  public static StreamProperties defaults() {
    return new StreamProperties(true, 3, 200, Duration.ofSeconds(15), Duration.ofMinutes(30), Duration.ofSeconds(30),
        2000, Duration.ofMinutes(5), Duration.ofSeconds(1), 1000, 3000, 50);
  }
}
