package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * VAPID (RFC 8292, §19.2) against the RFC's §2.4 example: the same claims give the same JOSE header and claims set
 * byte for byte, the RFC's own token verifies with its public key {@code k}, and our signatures verify with ours.
 */
class VapidJwtTest {

  /** RFC 8292 §2.4, Figure 1 (line wrapping removed). */
  static final String RFC_TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJhdWQiOiJodHRwczovL3B1c2guZXhhbXBsZS5uZXQiLCJleH"
      + "AiOjE0NTM1MjM3NjgsInN1YiI6Im1haWx0bzpwdXNoQGV4YW1wbGUuY29tIn0.i3CYb7t4xfxCDquptFOepC9GAu_HLGkMlMuCGSK2rpiUfnK9ojF"
      + "wDXb1JrErtmysazNjjvW2L9OkSSHzvoD1oA";
  static final String RFC_K = "BA1Hxzyi1RUM1b5wjxsn7nGxAszw2u61m164i3MrAIxHF6YK5h4SDYic-dRuU_RCPCfA5aq9ojSwk5Y2EmClBPs";

  @Test
  void theRfcExampleTokenVerifiesWithItsPublicKey() {
    ECPublicKey k = WebPushCrypto.decodePoint(WebPushCrypto.unb64(RFC_K));
    assertThat(VapidJwt.verify(RFC_TOKEN, k)).isTrue();
    assertThat(VapidJwt.verify(RFC_TOKEN.substring(0, RFC_TOKEN.length() - 2) + "AA", k)).isFalse();
    // the JWK of the example: x and y are the point's coordinates
    byte[] point = WebPushCrypto.encodePoint(k);
    assertThat(WebPushCrypto.b64(java.util.Arrays.copyOfRange(point, 1, 33)))
        .isEqualTo("DUfHPKLVFQzVvnCPGyfucbECzPDa7rWbXriLcysAjEc");
    assertThat(WebPushCrypto.b64(java.util.Arrays.copyOfRange(point, 33, 65)))
        .isEqualTo("F6YK5h4SDYic-dRuU_RCPCfA5aq9ojSwk5Y2EmClBPs");
  }

  @Test
  void ourTokenHasTheRfcHeaderAndClaimsAndASignatureThatVerifies() {
    KeyPair keys = WebPushCrypto.generateKeyPair();
    String jwt = VapidJwt.token("https://push.example.net", 1453523768L, "mailto:push@example.com",
        (ECPrivateKey) keys.getPrivate());
    String[] parts = jwt.split("\\.");
    String[] rfc = RFC_TOKEN.split("\\.");
    assertThat(parts).hasSize(3);
    assertThat(parts[0]).isEqualTo(rfc[0]);
    assertThat(parts[1]).isEqualTo(rfc[1]);
    assertThat(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
        .isEqualTo("{\"aud\":\"https://push.example.net\",\"exp\":1453523768,\"sub\":\"mailto:push@example.com\"}");
    assertThat(WebPushCrypto.unb64(parts[2])).as("ES256 R‖S").hasSize(64);
    assertThat(VapidJwt.verify(jwt, (ECPublicKey) keys.getPublic())).isTrue();
    assertThat(VapidJwt.verify(jwt, (ECPublicKey) WebPushCrypto.generateKeyPair().getPublic())).isFalse();
  }

  @Test
  void theAuthorizationHeaderAndTheAudience() {
    assertThat(VapidJwt.authorization("a.b.c", RFC_K)).isEqualTo("vapid t=a.b.c, k=" + RFC_K);
    assertThat(VapidJwt.audience(URI.create("https://fcm.googleapis.com/fcm/send/abc:def")))
        .isEqualTo("https://fcm.googleapis.com");
    assertThat(VapidJwt.audience(URI.create("https://Web.Push.Apple.com:443/QG9k"))).isEqualTo("https://web.push.apple.com");
    assertThat(VapidJwt.audience(URI.create("http://127.0.0.1:18330/push/1"))).isEqualTo("http://127.0.0.1:18330");
    assertThat(VapidJwt.jsonString("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\u000a\"");
  }
}
