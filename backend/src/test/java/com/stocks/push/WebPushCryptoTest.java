package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;

/**
 * RFC 8291 (Web Push message encryption, §19.2) against the RFC's own example: §5 and the intermediate values of
 * Appendix A. Values copied verbatim from https://www.rfc-editor.org/rfc/rfc8291.txt (whitespace removed).
 */
class WebPushCryptoTest {

  static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
  static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
  static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
  static final String ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
  static final String PRK_KEY = "Snr3JMxaHVDXHWJn5wdC52WjpCtd2EIEGBykDcZW32k";
  static final String KEY_INFO = "V2ViUHVzaDogaW5mbwAEJXGyvs3942BVGq8e0PTNNmwRzr5VX4m8t7GGpTM5FzFo7OLr4BhZe9MEebhuPI-OztV3"
      + "ylkYfpJGmQ22ggCLDgT-M_SrDepxkU21WCP3O1SUj0EwbZIHMtu5pZpTKGSCIA5Zent7wmC6HCJ5mFgJkuk5cwAvMBKiiujwa7t45ewP";
  static final String IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg";
  static final String PRK = "09_eUZGrsvxChDCGRCdkLiDXrReGOEVeSCdCcPBSJSc";
  static final String CEK = "oIhVW04MRdy2XN9CiKLxTg";
  static final String NONCE = "4h_95klXJ5E_qnoN";
  static final String HEADER = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmY"
      + "WAmS6TlzAC8wEqKK6PBru3jl7A8";
  static final String CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";
  /** §5: the whole message body. */
  static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmY"
      + "WAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

  static byte[] u(String b64) {
    return WebPushCrypto.unb64(b64);
  }

  static KeyPair senderKeys() {
    return new KeyPair(WebPushCrypto.decodePoint(u(AS_PUBLIC)), WebPushCrypto.decodeScalar(u(AS_PRIVATE)));
  }

  @Test
  void theRfc8291ExampleEncryptsToTheExactBody() {
    byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.US_ASCII),
        WebPushCrypto.decodePoint(u(UA_PUBLIC)), u(AUTH), senderKeys(), u(SALT));
    assertThat(WebPushCrypto.b64(body)).isEqualTo(BODY);
    // 86-byte header + 41-byte plaintext + 1 delimiter + 16-byte tag. The RFC's example request says
    // "Content-Length: 145", but its own base64url body decodes to 144 bytes (the header line is off by one).
    assertThat(body).hasSize(144).hasSize(u(BODY).length);
    assertThat(WebPushCrypto.b64(Arrays.copyOf(body, 86))).isEqualTo(HEADER);
    assertThat(WebPushCrypto.b64(Arrays.copyOfRange(body, 86, body.length))).isEqualTo(CIPHERTEXT);
  }

  @Test
  void everyIntermediateValueOfAppendixA() {
    ECPublicKey ua = WebPushCrypto.decodePoint(u(UA_PUBLIC));
    ECPrivateKey as = WebPushCrypto.decodeScalar(u(AS_PRIVATE));
    byte[] ecdh = WebPushCrypto.ecdh(as, ua);
    assertThat(WebPushCrypto.b64(ecdh)).isEqualTo(ECDH_SECRET);
    // the receiver derives the same secret from its own private key
    assertThat(WebPushCrypto.ecdh(WebPushCrypto.decodeScalar(u(UA_PRIVATE)), WebPushCrypto.decodePoint(u(AS_PUBLIC))))
        .isEqualTo(ecdh);
    assertThat(WebPushCrypto.b64(WebPushCrypto.hkdfExtract(u(AUTH), ecdh))).isEqualTo(PRK_KEY);
    byte[] keyInfo = WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), u(UA_PUBLIC),
        u(AS_PUBLIC));
    assertThat(WebPushCrypto.b64(keyInfo)).isEqualTo(KEY_INFO);
    byte[] ikm = WebPushCrypto.hkdf(u(AUTH), ecdh, keyInfo, 32);
    assertThat(WebPushCrypto.b64(ikm)).isEqualTo(IKM);
    byte[] prk = WebPushCrypto.hkdfExtract(u(SALT), ikm);
    assertThat(WebPushCrypto.b64(prk)).isEqualTo(PRK);
    assertThat(WebPushCrypto.b64(WebPushCrypto.hkdfExpand(prk,
        "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16))).isEqualTo(CEK);
    assertThat(WebPushCrypto.b64(WebPushCrypto.hkdfExpand(prk,
        "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12))).isEqualTo(NONCE);
  }

  @Test
  void keysRoundTripThroughTheirWireEncodings() {
    assertThat(WebPushCrypto.b64(WebPushCrypto.encodePoint(WebPushCrypto.decodePoint(u(AS_PUBLIC)))))
        .isEqualTo(AS_PUBLIC);
    assertThat(WebPushCrypto.b64(WebPushCrypto.encodeScalar(WebPushCrypto.decodeScalar(u(AS_PRIVATE)))))
        .isEqualTo(AS_PRIVATE);
    for (int i = 0; i < 20; i++) {                                     // leading zero bytes keep their width
      KeyPair kp = WebPushCrypto.generateKeyPair();
      byte[] point = WebPushCrypto.encodePoint((ECPublicKey) kp.getPublic());
      assertThat(point).hasSize(65);
      assertThat(WebPushCrypto.decodePoint(point).getW()).isEqualTo(((ECPublicKey) kp.getPublic()).getW());
      byte[] scalar = WebPushCrypto.encodeScalar((ECPrivateKey) kp.getPrivate());
      assertThat(scalar).hasSize(32);
      assertThat(WebPushCrypto.decodeScalar(scalar).getS()).isEqualTo(((ECPrivateKey) kp.getPrivate()).getS());
    }
  }

  @Test
  void aRandomMessageDecryptsOnTheReceiverSide() throws Exception {
    KeyPair receiver = WebPushCrypto.generateKeyPair();
    byte[] auth = new byte[16];
    new java.security.SecureRandom().nextBytes(auth);
    byte[] plaintext = "{\"title\":\"AAPL ≥ $230.00\"}".getBytes(StandardCharsets.UTF_8);
    byte[] body = WebPushCrypto.encrypt(plaintext, (ECPublicKey) receiver.getPublic(), auth);
    assertThat(decrypt(body, (ECPrivateKey) receiver.getPrivate(), (ECPublicKey) receiver.getPublic(), auth))
        .isEqualTo(plaintext);
    // a fresh salt and sender key every time
    assertThat(WebPushCrypto.encrypt(plaintext, (ECPublicKey) receiver.getPublic(), auth)).isNotEqualTo(body);
  }

  @Test
  void theRfcBodyDecryptsWithTheReceiversKey() throws Exception {
    byte[] plain = decrypt(u(BODY), WebPushCrypto.decodeScalar(u(UA_PRIVATE)), WebPushCrypto.decodePoint(u(UA_PUBLIC)),
        u(AUTH));
    assertThat(new String(plain, StandardCharsets.US_ASCII)).isEqualTo(PLAINTEXT);
  }

  @Test
  void invalidKeysAndSizesAreRefused() {
    byte[] point = u(UA_PUBLIC);
    byte[] offCurve = point.clone();
    offCurve[64] ^= 1;
    assertThatThrownBy(() -> WebPushCrypto.decodePoint(offCurve)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not on P-256");
    assertThatThrownBy(() -> WebPushCrypto.decodePoint(Arrays.copyOf(point, 64)))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] compressed = point.clone();
    compressed[0] = 0x02;
    assertThatThrownBy(() -> WebPushCrypto.decodePoint(compressed)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WebPushCrypto.decodeScalar(new byte[32])).isInstanceOf(IllegalArgumentException.class);
    ECPublicKey ua = WebPushCrypto.decodePoint(point);
    assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], ua, u(AUTH)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[1], ua, new byte[15]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void base64urlIsTolerantOfPaddingAndTheStandardAlphabet() {
    byte[] bytes = {(byte) 0xfb, (byte) 0xff, 0x01};
    assertThat(WebPushCrypto.b64(bytes)).isEqualTo("-_8B");
    assertThat(WebPushCrypto.unb64("+/8B")).isEqualTo(bytes);
    assertThat(WebPushCrypto.unb64(" -_8B== ")).isEqualTo(bytes);
    assertThatThrownBy(() -> WebPushCrypto.unb64("not base64!")).isInstanceOf(IllegalArgumentException.class);
  }

  /** The receiver's side of RFC 8291 §3.4 / RFC 8188 (one record), written independently of the sender. */
  static byte[] decrypt(byte[] body, ECPrivateKey uaPrivate, ECPublicKey uaPublic, byte[] auth) throws Exception {
    ByteBuffer b = ByteBuffer.wrap(body);
    byte[] salt = new byte[16];
    b.get(salt);
    int rs = b.getInt();
    assertThat(rs).isEqualTo(4096);
    int idLen = b.get() & 0xff;
    byte[] keyId = new byte[idLen];
    b.get(keyId);
    byte[] ciphertext = new byte[b.remaining()];
    b.get(ciphertext);
    ECPublicKey asPublic = WebPushCrypto.decodePoint(keyId);
    byte[] ecdh = WebPushCrypto.ecdh(uaPrivate, asPublic);
    byte[] info = WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
        WebPushCrypto.encodePoint(uaPublic), keyId);
    byte[] ikm = WebPushCrypto.hkdf(auth, ecdh, info, 32);
    byte[] prk = WebPushCrypto.hkdfExtract(salt, ikm);
    byte[] cek = WebPushCrypto.hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
    byte[] nonce = WebPushCrypto.hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
    byte[] padded = WebPushCrypto.aesGcm(Cipher.DECRYPT_MODE, cek, nonce, ciphertext);
    int end = padded.length - 1;
    while (end >= 0 && padded[end] == 0) {
      end--;
    }
    assertThat(padded[end]).as("last-record delimiter").isEqualTo((byte) 0x02);
    return Arrays.copyOf(padded, end);
  }
}
