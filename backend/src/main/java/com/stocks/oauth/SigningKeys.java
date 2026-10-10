package com.stocks.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The RSA key that signs the access tokens (§17.9.4), one at a time:
 * <ol>
 *   <li>{@code OAUTH_JWK_PEM_FILE} ({@code stocks.oauth.jwk-pem-file}): an RSA private key ≥ 2048 bits in PKCS#8
 *       ({@code BEGIN PRIVATE KEY}) or PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) PEM. An unreadable or invalid file is
 *       logged as an ERROR and the persisted key is used instead, so a typo never stops the app.</li>
 *   <li>Otherwise the newest live {@code oauth_signing_key} row, decrypted with AES-256-GCM under a key derived from
 *       {@code API_KEY_PEPPER} (HKDF-SHA256, info {@value #HKDF_INFO}, the {@code kid} as associated data). A row the
 *       current pepper cannot decrypt is retired and a new key is generated (RSA 2048) and stored.</li>
 *   <li>Without a pepper: an in-memory key (WARN), so tokens die with the process and clients refresh.</li>
 * </ol>
 * The {@code kid} is the RFC 7638 thumbprint. Loaded lazily (and once at startup by {@link OAuthConfig}), so a
 * database problem never blocks the boot.
 */
@Component
public class SigningKeys {

  private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
  static final String HKDF_INFO = "stocks-oauth-jwk-v1";
  static final int MIN_BITS = 2048;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final StocksProperties stocks;
  private final OAuthProperties properties;
  private volatile RSAKey current;

  public SigningKeys(JdbcClient jdbc, TimeSource time, StocksProperties stocks, OAuthProperties properties) {
    this.jdbc = jdbc;
    this.time = time;
    this.stocks = stocks;
    this.properties = properties;
  }

  /** The signing key (private part included). */
  public RSAKey current() {
    RSAKey key = current;
    if (key == null) {
      synchronized (this) {
        key = current;
        if (key == null) {
          key = load();
          current = key;
        }
      }
    }
    return key;
  }

  /** The public JWK set served at {@code /oauth2/jwks}. */
  public Map<String, Object> publicJwkSet() {
    return new JWKSet(current().toPublicJWK()).toJSONObject(true);
  }

  /** Forgets the loaded key (tests). */
  void reload() {
    current = null;
  }

  private RSAKey load() {
    String file = properties.jwkPemFile();
    if (file != null && !file.isBlank()) {
      try {
        RSAKey key = toJwk(parsePem(Files.readString(Path.of(file.strip()), StandardCharsets.US_ASCII)));
        log.info("OAuth signing key loaded from OAUTH_JWK_PEM_FILE (kid {})", key.getKeyID());
        return key;
      } catch (IOException | GeneralSecurityException | IllegalArgumentException | JOSEException e) {
        log.error("OAUTH_JWK_PEM_FILE '{}' is not a usable RSA private key ({}); using the persisted key instead",
            file, e.getMessage());
      }
    }
    if (!stocks.security().hasApiKeyPepper()) {
      log.warn("No API_KEY_PEPPER: the OAuth signing key lives in memory only; access tokens end with the process");
      return generate();
    }
    byte[] aesKey = hkdf(stocks.security().apiKeyPepper().getBytes(StandardCharsets.UTF_8));
    Optional<StoredKey> stored = jdbc.sql("""
            SELECT kid, private_key_enc FROM oauth_signing_key WHERE retired_at IS NULL
             ORDER BY created_at DESC, kid""")
        .query((rs, n) -> new StoredKey(rs.getString("kid"), rs.getString("private_key_enc")))
        .list().stream().findFirst();
    if (stored.isPresent()) {
      try {
        RSAKey key = toJwk(privateKey(decrypt(aesKey, stored.get().kid(), stored.get().privateKeyEnc())));
        if (key.getKeyID().equals(stored.get().kid())) {
          return key;
        }
        log.warn("OAuth signing key {} does not match its thumbprint; generating a new one", stored.get().kid());
      } catch (GeneralSecurityException | JOSEException | IllegalArgumentException e) {
        log.warn("OAuth signing key {} cannot be decrypted with the current API_KEY_PEPPER ({}); generating a new "
            + "one (outstanding access tokens stop working; clients refresh)", stored.get().kid(), e.toString());
      }
      jdbc.sql("UPDATE oauth_signing_key SET retired_at = ? WHERE kid = ?")
          .params(Jdbc.ts(time.now()), stored.get().kid()).update();
    }
    RSAKey key = generate();
    try {
      byte[] pkcs8 = key.toRSAPrivateKey().getEncoded();
      jdbc.sql("INSERT INTO oauth_signing_key (kid, public_jwk, private_key_enc, created_at) VALUES (?, ?, ?, ?)")
          .params(key.getKeyID(), key.toPublicJWK().toJSONString(), encrypt(aesKey, key.getKeyID(), pkcs8),
              Jdbc.ts(time.now()))
          .update();
      log.info("OAuth signing key generated and stored encrypted (kid {})", key.getKeyID());
    } catch (GeneralSecurityException | JOSEException e) {
      throw new IllegalStateException("Could not store the OAuth signing key", e);
    }
    return key;
  }

  private record StoredKey(String kid, String privateKeyEnc) {}

  static RSAKey generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(MIN_BITS, RANDOM);
      KeyPair pair = generator.generateKeyPair();
      return toJwk((RSAPrivateCrtKey) pair.getPrivate());
    } catch (GeneralSecurityException | JOSEException e) {
      throw new IllegalStateException("Could not generate an RSA key", e);
    }
  }

  static RSAKey toJwk(RSAPrivateCrtKey privateKey) throws GeneralSecurityException, JOSEException {
    if (privateKey.getModulus().bitLength() < MIN_BITS) {
      throw new IllegalArgumentException("the RSA key has fewer than " + MIN_BITS + " bits");
    }
    RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
        .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    return new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
  }

  static RSAPrivateCrtKey privateKey(byte[] pkcs8) throws GeneralSecurityException {
    if (!(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)) instanceof RSAPrivateCrtKey k)) {
      throw new IllegalArgumentException("not an RSA CRT private key");
    }
    return k;
  }

  /** A PKCS#8 or PKCS#1 RSA private key in PEM. */
  static RSAPrivateCrtKey parsePem(String pem) throws GeneralSecurityException {
    String type;
    if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
      type = "PRIVATE KEY";
    } else if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
      type = "RSA PRIVATE KEY";
    } else {
      throw new IllegalArgumentException("expected an unencrypted 'BEGIN PRIVATE KEY' or 'BEGIN RSA PRIVATE KEY' PEM");
    }
    int start = pem.indexOf("-----BEGIN " + type + "-----") + ("-----BEGIN " + type + "-----").length();
    int end = pem.indexOf("-----END " + type + "-----", start);
    if (end < 0) {
      throw new IllegalArgumentException("the PEM has no END line");
    }
    String base64 = pem.substring(start, end).replaceAll("\\s+", "");
    if (base64.contains(":")) {
      throw new IllegalArgumentException("encrypted PEM keys are not supported");
    }
    byte[] der = Base64.getDecoder().decode(base64);
    return privateKey(type.equals("PRIVATE KEY") ? der : pkcs1ToPkcs8(der));
  }

  /** Wraps a PKCS#1 {@code RSAPrivateKey} in a PKCS#8 {@code PrivateKeyInfo} (rsaEncryption, NULL parameters). */
  static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
    byte[] version = {0x02, 0x01, 0x00};
    byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
        0x01, 0x05, 0x00};
    byte[] octets = der(0x04, pkcs1);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(version);
    body.writeBytes(algorithm);
    body.writeBytes(octets);
    return der(0x30, body.toByteArray());
  }

  private static byte[] der(int tag, byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int len = content.length;
    if (len < 0x80) {
      out.write(len);
    } else if (len < 0x100) {
      out.write(0x81);
      out.write(len);
    } else if (len < 0x10000) {
      out.write(0x82);
      out.write(len >> 8);
      out.write(len & 0xff);
    } else {
      out.write(0x83);
      out.write(len >> 16);
      out.write((len >> 8) & 0xff);
      out.write(len & 0xff);
    }
    out.writeBytes(content);
    return out.toByteArray();
  }

  /** HKDF-SHA256 (RFC 5869) with an all-zero salt: 32 bytes for AES-256. */
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

  static String encrypt(byte[] aesKey, String kid, byte[] plaintext) throws GeneralSecurityException {
    byte[] iv = new byte[12];
    RANDOM.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
    cipher.updateAAD(kid.getBytes(StandardCharsets.US_ASCII));
    byte[] sealed = cipher.doFinal(plaintext);
    byte[] out = new byte[iv.length + sealed.length];
    System.arraycopy(iv, 0, out, 0, iv.length);
    System.arraycopy(sealed, 0, out, iv.length, sealed.length);
    return Base64.getEncoder().encodeToString(out);
  }

  static byte[] decrypt(byte[] aesKey, String kid, String stored) throws GeneralSecurityException {
    byte[] in = Base64.getDecoder().decode(stored);
    if (in.length < 12 + 16) {
      throw new GeneralSecurityException("ciphertext too short");
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, in, 0, 12));
    cipher.updateAAD(kid.getBytes(StandardCharsets.US_ASCII));
    return cipher.doFinal(in, 12, in.length - 12);
  }
}
