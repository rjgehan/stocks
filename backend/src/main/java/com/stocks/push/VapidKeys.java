package com.stocks.push;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The server's VAPID key pair (RFC 8292, §19.2) and the {@code Authorization} header it signs.
 *
 * <p>Source, in order: {@code stocks.push.vapid.public-key} + {@code private-key} when both are set (env
 * {@code STOCKS_PUSH_VAPID_PUBLIC_KEY} / {@code _PRIVATE_KEY}); otherwise the one row of {@code push_vapid_key},
 * generated and stored on first use, so production needs no new environment and the key survives restarts and
 * upgrades (subscriptions are bound to it). A broken, mismatched or half-configured pair logs an ERROR and makes Web
 * Push unavailable; the app still boots and notifications still work in the app.
 *
 * <p>JWTs ({@code aud} = the push service origin, {@code exp} = now + 12 h, {@code sub}) are cached per audience and
 * re-signed when less than an hour is left.
 */
@Component
public class VapidKeys {

  private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);

  static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
  static final Duration TOKEN_MIN_LEFT = Duration.ofHours(1);
  static final String FALLBACK_SUBJECT = "mailto:admin@localhost";

  /** A usable key pair. */
  public record Pair(ECPublicKey publicKey, ECPrivateKey privateKey, String publicKeyB64, String source) {}

  private record Token(String jwt, Instant expiresAt) {}

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final PushProperties props;
  private final String subject;
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private volatile Optional<Pair> pair;
  private volatile String status = "not loaded";

  public VapidKeys(JdbcClient jdbc, TimeSource time, PushProperties props, StocksProperties stocks) {
    this.jdbc = jdbc;
    this.time = time;
    this.props = props;
    this.subject = subject(props.vapid().subject(), stocks.publicUrl());
  }

  /** {@code stocks.push.vapid.subject}, else the public URL when it is https, else {@value #FALLBACK_SUBJECT}. */
  static String subject(String configured, String publicUrl) {
    if (configured != null && !configured.isBlank()) {
      return configured.strip();
    }
    if (publicUrl != null && publicUrl.startsWith("https://")) {
      return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }
    return FALLBACK_SUBJECT;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void logStatus() {
    Optional<Pair> p = current();
    if (p.isPresent()) {
      log.info("Web Push ready (VAPID key from {}, subject {})", p.get().source(), subject);
    } else {
      log.info("Web Push unavailable: {}", status);
    }
  }

  /** The key pair, or empty when Web Push is off or has no usable key. Loads (and generates) on first call. */
  public Optional<Pair> current() {
    Optional<Pair> p = pair;
    if (p == null) {
      synchronized (this) {
        p = pair;
        if (p == null) {
          p = load();
          pair = p;
        }
      }
    }
    return p;
  }

  /** Why Web Push is unavailable (or "ok"). */
  public String status() {
    current();
    return status;
  }

  public String subject() {
    return subject;
  }

  /** {@code vapid t=<jwt>, k=<public key>} for the push service at {@code endpoint}. */
  public String authorization(URI endpoint) {
    Pair p = current().orElseThrow(() -> new IllegalStateException("Web Push is unavailable: " + status));
    String aud = VapidJwt.audience(endpoint);
    Instant now = time.now();
    Token t = tokens.get(aud);
    if (t == null || Duration.between(now, t.expiresAt()).compareTo(TOKEN_MIN_LEFT) < 0) {
      Instant exp = now.plus(TOKEN_LIFETIME);
      t = new Token(VapidJwt.token(aud, exp.getEpochSecond(), subject, p.privateKey()), exp);
      tokens.put(aud, t);
    }
    return VapidJwt.authorization(t.jwt(), p.publicKeyB64());
  }

  // ------------------------------------------------------------------------------------------- loading

  private Optional<Pair> load() {
    if (!props.enabled()) {
      status = "disabled (stocks.push.enabled=false)";
      return Optional.empty();
    }
    PushProperties.Vapid v = props.vapid();
    if (v.half()) {
      status = "only one of stocks.push.vapid.public-key / private-key is set";
      log.error("Web Push is off: {} (set both, or neither to use the generated key)", status);
      return Optional.empty();
    }
    if (v.configured()) {
      return checked(v.publicKey(), v.privateKey(), "configuration");
    }
    try {
      Optional<String[]> stored = stored();
      if (stored.isEmpty()) {
        KeyPair kp = WebPushCrypto.generateKeyPair();
        String pub = WebPushCrypto.b64(WebPushCrypto.encodePoint((ECPublicKey) kp.getPublic()));
        String priv = WebPushCrypto.b64(WebPushCrypto.encodeScalar((ECPrivateKey) kp.getPrivate()));
        try {
          jdbc.sql("INSERT INTO push_vapid_key (id, public_key, private_key, created_at) VALUES (1, ?, ?, ?)")
              .params(pub, priv, Jdbc.ts(time.now())).update();
          log.info("Generated the Web Push VAPID key pair (stored in push_vapid_key)");
        } catch (DataAccessException e) {
          log.debug("VAPID key insert lost a race; reading the stored one: {}", e.toString());
        }
        stored = stored();
      }
      if (stored.isEmpty()) {
        status = "no VAPID key could be stored";
        log.error("Web Push is off: {}", status);
        return Optional.empty();
      }
      return checked(stored.get()[0], stored.get()[1], "database");
    } catch (DataAccessException e) {
      status = "the VAPID key could not be read: " + e.getClass().getSimpleName();
      log.error("Web Push is off: {}", status, e);
      return Optional.empty();
    }
  }

  private Optional<String[]> stored() {
    return jdbc.sql("SELECT public_key, private_key FROM push_vapid_key WHERE id = 1")
        .query((rs, n) -> new String[] {rs.getString("public_key"), rs.getString("private_key")}).optional();
  }

  /** Decodes the pair and proves it matches with a sign/verify round trip. */
  private Optional<Pair> checked(String publicB64, String privateB64, String source) {
    try {
      ECPublicKey pub = WebPushCrypto.decodePoint(WebPushCrypto.unb64(publicB64));
      ECPrivateKey priv = WebPushCrypto.decodeScalar(WebPushCrypto.unb64(privateB64));
      String probe = VapidJwt.token("https://vapid.check", 1L, subject, priv);
      if (!VapidJwt.verify(probe, pub)) {
        status = "the VAPID public and private keys from " + source + " do not match";
        log.error("Web Push is off: {}", status);
        return Optional.empty();
      }
      status = "ok";
      return Optional.of(new Pair(pub, priv, WebPushCrypto.b64(WebPushCrypto.encodePoint(pub)), source));
    } catch (RuntimeException e) {
      status = "the VAPID key from " + source + " is not a valid P-256 key pair";
      log.error("Web Push is off: {} ({})", status, e.getMessage());
      return Optional.empty();
    }
  }
}
