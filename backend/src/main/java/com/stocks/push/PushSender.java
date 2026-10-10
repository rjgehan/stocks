package com.stocks.push;

import com.stocks.common.time.TimeSource;
import com.stocks.push.PushSubscriptions.Subscription;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sends one encrypted Web Push message to one subscription (§19.2): RFC 8291 {@code aes128gcm} body
 * ({@link WebPushCrypto}), RFC 8292 VAPID {@code Authorization} ({@link VapidKeys}), RFC 8030 {@code TTL},
 * {@code Urgency} and {@code Topic}. The JDK {@code HttpClient} never follows redirects and times out; the endpoint is
 * re-checked against {@link EndpointPolicy} before every request. With {@code dry-run} it encrypts and signs but sends
 * nothing. Never logs an endpoint, key or payload.
 */
@Component
public class PushSender {

  private static final Logger log = LoggerFactory.getLogger(PushSender.class);

  /** Upper bound for a push service's {@code Retry-After}. */
  static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);

  /** What happened to one delivery attempt. */
  public enum Result { DELIVERED, GONE, RETRY, FAILED }

  /** @param status the HTTP status, or 0 when none (I/O error, timeout, refused endpoint, dry run = 201) */
  public record Outcome(Result result, int status, Duration retryAfter) {
    static Outcome of(Result r, int status) {
      return new Outcome(r, status, null);
    }
  }

  private final VapidKeys keys;
  private final PushProperties props;
  private final TimeSource time;
  private final EndpointPolicy policy;
  private final HttpClient http;

  @Autowired
  public PushSender(VapidKeys keys, PushProperties props, TimeSource time) {
    this(keys, props, time, HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build());
  }

  PushSender(VapidKeys keys, PushProperties props, TimeSource time, HttpClient http) {
    this.keys = keys;
    this.props = props;
    this.time = time;
    this.policy = EndpointPolicy.of(props);
    this.http = http;
  }

  /** One attempt; never throws. */
  public Outcome send(Subscription sub, PushMessage message, byte[] payload) {
    Optional<URI> endpoint = policy.check(sub.endpoint());
    if (endpoint.isEmpty()) {
      return Outcome.of(Result.GONE, 0);                    // no longer an allowed push service: drop it
    }
    byte[] body;
    String authorization;
    try {
      body = WebPushCrypto.encrypt(payload, WebPushCrypto.decodePoint(WebPushCrypto.unb64(sub.p256dh())),
          WebPushCrypto.unb64(sub.authSecret()));
      authorization = keys.authorization(endpoint.get());
    } catch (RuntimeException e) {
      log.warn("Push to device {} not sent: {}", sub.publicId(), e.getClass().getSimpleName());
      return Outcome.of(Result.FAILED, 0);
    }
    if (props.dryRun()) {
      return Outcome.of(Result.DELIVERED, 201);
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.get())
        .timeout(props.requestTimeout())
        .header("Content-Type", "application/octet-stream")
        .header("Content-Encoding", "aes128gcm")
        .header("TTL", Long.toString(Math.max(0, message.ttl().toSeconds())))
        .header("Urgency", message.urgency())
        .header("Authorization", authorization)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (message.topic() != null) {
      request.header("Topic", message.topic());
    }
    try {
      HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status == 200 || status == 201 || status == 202) {
        return Outcome.of(Result.DELIVERED, status);
      }
      if (status == 404 || status == 410) {
        return Outcome.of(Result.GONE, status);
      }
      if (status == 429 || status >= 500) {
        return new Outcome(Result.RETRY, status,
            retryAfter(response.headers().firstValue("Retry-After").orElse(null), time.now()));
      }
      log.info("Push to device {} refused: HTTP {}", sub.publicId(), status);
      return Outcome.of(Result.FAILED, status);
    } catch (IOException e) {
      log.debug("Push to device {} failed: {}", sub.publicId(), e.getClass().getSimpleName());
      return Outcome.of(Result.RETRY, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.of(Result.FAILED, 0);
    } catch (RuntimeException e) {
      log.warn("Push to device {} failed: {}", sub.publicId(), e.getClass().getSimpleName());
      return Outcome.of(Result.FAILED, 0);
    }
  }

  /** {@code Retry-After} as seconds or an HTTP date, capped at {@link #MAX_RETRY_AFTER}; null when absent or bad. */
  static Duration retryAfter(String header, Instant now) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String h = header.strip();
    try {
      long seconds = Long.parseLong(h);
      return seconds < 0 ? null : min(Duration.ofSeconds(seconds));
    } catch (NumberFormatException e) {
      try {
        Instant at = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        Duration d = Duration.between(now, at);
        return min(d.isNegative() ? Duration.ZERO : d);
      } catch (DateTimeParseException ex) {
        return null;
      }
    }
  }

  private static Duration min(Duration d) {
    return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
  }
}
