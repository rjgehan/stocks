package com.stocks.push;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
 * <p><b>The stored private key is encrypted</b> (§19.2), like the OAuth signing key (§17.9.4): AES-256-GCM under
 * HKDF-SHA256({@code API_KEY_PEPPER}, info {@value #HKDF_INFO}), stored as {@code v1:<base64(iv ‖ ciphertext)>}
 * (83 characters, within the column's 100), so a database dump alone cannot send authenticated pushes to every
 * player's devices. A plain-text key stored by an earlier version is accepted once and rewritten encrypted. A stored
 * key the current pepper cannot decrypt (the pepper changed) is replaced by a new pair and the subscriptions bound to
 * the old one are deleted (the SPA subscribes again at its next start, §19.2). Without a pepper the key lives in
 * memory only (WARN): subscriptions then last until the next restart.
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
  static final String HKDF_INFO = "stocks-vapid-v1";
  static final String SEALED_PREFIX = "v1:";
  private static final byte[] AAD = "push_vapid_key".getBytes(StandardCharsets.US_ASCII);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** A usable key pair. */
  public record Pair(ECPublicKey publicKey, ECPrivateKey privateKey, String publicKeyB64, String source) {}

  private record Token(String jwt, Instant expiresAt) {}

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final PushProperties props;
  private final String subject;
  /** The AES key derived from the pepper, or null without one. */
  private final byte[] aesKey;
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private volatile Optional<Pair> pair;
  private volatile String status = "not loaded";

  public VapidKeys(JdbcClient jdbc, TimeSource time, PushProperties props, StocksProperties stocks) {
    this.jdbc = jdbc;
    this.time = time;
    this.props = props;
    this.subject = subject(props.vapid().subject(), stocks.publicUrl());
    this.aesKey = stocks.security().hasApiKeyPepper()
        ? hkdf(stocks.security().apiKeyPepper().getBytes(StandardCharsets.UTF_8)) : null;
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
    if (aesKey == null) {
      // no pepper to seal with: an earlier plain-text key still works; otherwise a key in memory only
      Optional<String[]> plain;
      try {
        plain = stored().filter(r -> r[1] != null && !r[1].startsWith(SEALED_PREFIX));
      } catch (DataAccessException e) {
        plain = Optional.empty();
      }
      if (plain.isPresent()) {
        return checked(plain.get()[0], plain.get()[1], "database");
      }
      KeyPair kp = WebPushCrypto.generateKeyPair();
      log.warn("No API_KEY_PEPPER: the Web Push VAPID key lives in memory only; subscriptions end with the process");
      return checked(publicOf(kp), privateOf(kp), "memory");
    }
    try {
      Optional<String[]> stored = stored();
      if (stored.isPresent()) {
        String[] row = stored.get();
        String priv = row[1];
        if (priv != null && priv.startsWith(SEALED_PREFIX)) {
          try {
            return checked(row[0], WebPushCrypto.b64(open(aesKey, priv)), "database");
          } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("The stored VAPID key cannot be decrypted with the current API_KEY_PEPPER ({}); generating a new "
                + "pair (every device subscribes again)", e.toString());
            jdbc.sql("DELETE FROM push_subscription").update();
            jdbc.sql("DELETE FROM push_vapid_key WHERE id = 1 AND private_key = ?").param(priv).update();
            stored = stored();
          }
        } else if (priv != null) {
          // a plain-text key from before encryption: keep the pair (subscriptions are bound to it), store it sealed
          Optional<Pair> legacy = checked(row[0], priv, "database");
          if (legacy.isPresent()) {
            try {
              jdbc.sql("UPDATE push_vapid_key SET private_key = ? WHERE id = 1 AND private_key = ?")
                  .params(seal(aesKey, WebPushCrypto.unb64(priv)), priv).update();
              log.info("Encrypted the stored Web Push VAPID private key");
            } catch (GeneralSecurityException | DataAccessException e) {
              log.warn("Could not encrypt the stored VAPID key (kept as is): {}", e.toString());
            }
          }
          return legacy;
        }
      }
      if (stored.isEmpty()) {
        KeyPair kp = WebPushCrypto.generateKeyPair();
        try {
          jdbc.sql("INSERT INTO push_vapid_key (id, public_key, private_key, created_at) VALUES (1, ?, ?, ?)")
              .params(publicOf(kp), seal(aesKey, WebPushCrypto.unb64(privateOf(kp))), Jdbc.ts(time.now()))
              .update();
          log.info("Generated the Web Push VAPID key pair (stored encrypted in push_vapid_key)");
        } catch (DataAccessException e) {
          log.debug("VAPID key insert lost a race; reading the stored one: {}", e.toString());
        } catch (GeneralSecurityException e) {
          status = "the VAPID key could not be encrypted: " + e.getClass().getSimpleName();
          log.error("Web Push is off: {}", status, e);
          return Optional.empty();
        }
        stored = stored();
      }
      if (stored.isEmpty() || stored.get()[1] == null || !stored.get()[1].startsWith(SEALED_PREFIX)) {
        status = "no VAPID key could be stored";
        log.error("Web Push is off: {}", status);
        return Optional.empty();
      }
      try {
        return checked(stored.get()[0], WebPushCrypto.b64(open(aesKey, stored.get()[1])), "database");
      } catch (GeneralSecurityException | IllegalArgumentException e) {
        status = "the stored VAPID key cannot be decrypted";
        log.error("Web Push is off: {} ({})", status, e.toString());
        return Optional.empty();
      }
    } catch (DataAccessException e) {
      status = "the VAPID key could not be read: " + e.getClass().getSimpleName();
      log.error("Web Push is off: {}", status, e);
      return Optional.empty();
    }
  }

  private static String publicOf(KeyPair kp) {
    return WebPushCrypto.b64(WebPushCrypto.encodePoint((ECPublicKey) kp.getPublic()));
  }

  private static String privateOf(KeyPair kp) {
    return WebPushCrypto.b64(WebPushCrypto.encodeScalar((ECPrivateKey) kp.getPrivate()));
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

  // ------------------------------------------------------------------------------------------- sealing

  /** HKDF-SHA256 (RFC 5869, zero salt, info {@value #HKDF_INFO}): the 32-byte AES key. */
  static byte[] hkdf(byte[] ikm) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      byte[] prk = mac.doFinal(ikm);
      mac.init(new SecretKeySpec(prk, "HmacSHA256"));
      mac.update(HKDF_INFO.getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 1);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code v1:} + base64(IV ‖ AES-256-GCM(scalar)), AAD {@code push_vapid_key}. */
  static String seal(byte[] key, byte[] plaintext) throws GeneralSecurityException {
    byte[] iv = new byte[12];
    RANDOM.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    cipher.updateAAD(AAD);
    byte[] sealed = cipher.doFinal(plaintext);
    byte[] out = new byte[iv.length + sealed.length];
    System.arraycopy(iv, 0, out, 0, iv.length);
    System.arraycopy(sealed, 0, out, iv.length, sealed.length);
    return SEALED_PREFIX + Base64.getEncoder().encodeToString(out);
  }

  /** The inverse of {@link #seal}. */
  static byte[] open(byte[] key, String stored) throws GeneralSecurityException {
    byte[] in = Base64.getDecoder().decode(stored.substring(SEALED_PREFIX.length()));
    if (in.length < 12 + 16) {
      throw new GeneralSecurityException("ciphertext too short");
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
    cipher.updateAAD(AAD);
    return cipher.doFinal(in, 12, in.length - 12);
  }
}
