package com.stocks.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Locale;

/**
 * VAPID (RFC 8292, §19.2): the ES256 JWT that authenticates this server to a push service, and the
 * {@code Authorization: vapid t=<jwt>, k=<public key>} header. The JWS signature is the raw {@code R ‖ S} form
 * (JWS ES256, RFC 7518 §3.4), which the JDK provides as {@code SHA256withECDSAinP1363Format}. Checked against the
 * RFC 8292 §2.4 example ({@code VapidJwtTest}).
 */
public final class VapidJwt {

  /** The JOSE header, in the RFC 8292 example's member order. */
  static final String HEADER = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";
  private static final String ALGORITHM = "SHA256withECDSAinP1363Format";

  private VapidJwt() {}

  /** A signed JWT {@code {aud, exp, sub}} (member order as in RFC 8292 §2.4). */
  public static String token(String audience, long expiresAtEpochSeconds, String subject, ECPrivateKey key) {
    String claims = "{\"aud\":" + jsonString(audience) + ",\"exp\":" + expiresAtEpochSeconds + ",\"sub\":"
        + jsonString(subject) + "}";
    String signingInput = b64(HEADER) + "." + b64(claims);
    try {
      Signature s = Signature.getInstance(ALGORITHM);
      s.initSign(key);
      s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + WebPushCrypto.b64(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot sign the VAPID JWT", e);
    }
  }

  /** {@code vapid t=<jwt>, k=<base64url public key>} (RFC 8292 §3). */
  public static String authorization(String jwt, String publicKeyB64) {
    return "vapid t=" + jwt + ", k=" + publicKeyB64;
  }

  /** Whether {@code jwt} carries a valid ES256 signature by {@code key} (tests and the boot key check). */
  public static boolean verify(String jwt, ECPublicKey key) {
    int last = jwt.lastIndexOf('.');
    if (last < 0) {
      return false;
    }
    try {
      Signature s = Signature.getInstance(ALGORITHM);
      s.initVerify(key);
      s.update(jwt.substring(0, last).getBytes(StandardCharsets.US_ASCII));
      return s.verify(WebPushCrypto.unb64(jwt.substring(last + 1)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  /** The JWT {@code aud}: the push resource's origin, {@code scheme://host[:port]} (no default port). */
  public static String audience(URI endpoint) {
    String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
    int port = endpoint.getPort();
    boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
    return scheme + "://" + endpoint.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
  }

  private static String b64(String json) {
    return WebPushCrypto.b64(json.getBytes(StandardCharsets.UTF_8));
  }

  /** A JSON string literal (the inputs are a URL origin and a mailto/https subject, but escape anyway). */
  static String jsonString(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
