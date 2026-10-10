package com.stocks.push;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 2 Web Push, {@code stocks.push} (§19.2, §23.1). Every key has a safe default, so production boots with its
 * current {@code .env}: without {@code vapid.*} the key pair is generated on first boot and kept in the database.
 *
 * @param enabled               false: {@code GET /me/push} says unavailable, writes answer {@code PUSH_UNAVAILABLE}
 * @param vapid                 optional key pair override (base64url, as {@code web-push generate-vapid-keys} prints
 *                              it) and the JWT {@code sub}
 * @param allowedEndpointHosts  push services a subscription may point at ({@code *.} = any subdomain): the SSRF guard
 * @param allowInsecureEndpoints tests only: http, any host and port (a local fake push service)
 * @param dryRun                encrypt and sign but send nothing (the e2e profile)
 * @param threads               delivery threads (virtual)
 * @param queueCapacity         queued deliveries beyond the threads; overflow is dropped and logged
 * @param connectTimeout        HTTP connect timeout to a push service
 * @param requestTimeout        HTTP request timeout to a push service
 * @param maxAttempts           attempts for 429, 5xx and I/O errors (retried after 2 s and 10 s, or Retry-After)
 * @param maxDevicesPerPlayer   subscriptions per player; the oldest is dropped
 */
@ConfigurationProperties("stocks.push")
public record PushProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue Vapid vapid,
    @DefaultValue({"fcm.googleapis.com", "android.googleapis.com", "updates.push.services.mozilla.com",
        "*.push.services.mozilla.com", "web.push.apple.com", "*.push.apple.com", "*.notify.windows.com"})
    List<String> allowedEndpointHosts,
    @DefaultValue("false") boolean allowInsecureEndpoints,
    @DefaultValue("false") boolean dryRun,
    @DefaultValue("4") int threads,
    @DefaultValue("1000") int queueCapacity,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("10s") Duration requestTimeout,
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("10") int maxDevicesPerPlayer) {

  /** The VAPID override: both keys or neither; {@code subject} defaults to the public URL. */
  public record Vapid(@DefaultValue("") String publicKey, @DefaultValue("") String privateKey,
                      @DefaultValue("") String subject) {

    /** True when both keys are set. */
    public boolean configured() {
      return publicKey != null && !publicKey.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    /** True when exactly one key is set (a misconfiguration). */
    public boolean half() {
      boolean pub = publicKey != null && !publicKey.isBlank();
      boolean priv = privateKey != null && !privateKey.isBlank();
      return pub != priv;
    }
  }

  public static final List<String> DEFAULT_HOSTS = List.of("fcm.googleapis.com", "android.googleapis.com",
      "updates.push.services.mozilla.com", "*.push.services.mozilla.com", "web.push.apple.com", "*.push.apple.com",
      "*.notify.windows.com");

  /** The defaults, for tests and components built without Spring. */
  public static PushProperties defaults() {
    return new PushProperties(true, new Vapid("", "", ""), DEFAULT_HOSTS, false, false, 4, 1000,
        Duration.ofSeconds(5), Duration.ofSeconds(10), 3, 10);
  }

  /** A copy for a local fake push service (tests): http and any host allowed. */
  public PushProperties insecureForTests() {
    return new PushProperties(enabled, vapid, allowedEndpointHosts, true, dryRun, threads, queueCapacity,
        connectTimeout, requestTimeout, maxAttempts, maxDevicesPerPlayer);
  }
}
