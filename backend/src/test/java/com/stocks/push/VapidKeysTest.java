package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19.2 VAPID key lifecycle: generated on first use and persisted (so a restart, or a second instance on the same
 * database, keeps the key subscriptions are bound to); an env override wins; a broken, mismatched or half
 * configuration turns push off without failing; JWTs are cached per audience until an hour before expiry.
 */
class VapidKeysTest {

  TestDb db;
  MutableTimeSource time;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
  }

  @AfterEach
  void tearDown() {
    if (db.dataSource() instanceof com.zaxxer.hikari.HikariDataSource pool) {
      pool.close();
    }
  }

  VapidKeys keys(PushProperties props) {
    return new VapidKeys(db.jdbc(), time, props, TestProperties.stocks());
  }

  static PushProperties withVapid(String pub, String priv, String subject) {
    PushProperties d = PushProperties.defaults();
    return new PushProperties(true, new PushProperties.Vapid(pub, priv, subject), d.allowedEndpointHosts(), false,
        false, 4, 1000, d.connectTimeout(), d.requestTimeout(), 3, 10);
  }

  @Test
  void theKeyIsGeneratedOnceAndSurvivesARestart() {
    VapidKeys first = keys(PushProperties.defaults());
    VapidKeys.Pair a = first.current().orElseThrow();
    assertThat(a.source()).isEqualTo("database");
    assertThat(WebPushCrypto.unb64(a.publicKeyB64())).hasSize(65);
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM push_vapid_key").query(Integer.class).single()).isEqualTo(1);
    VapidKeys.Pair b = keys(PushProperties.defaults()).current().orElseThrow();
    assertThat(b.publicKeyB64()).isEqualTo(a.publicKeyB64());
    assertThat(first.status()).isEqualTo("ok");
  }

  @Test
  void anEnvironmentPairWinsAndIsChecked() {
    KeyPair kp = WebPushCrypto.generateKeyPair();
    String pub = WebPushCrypto.b64(WebPushCrypto.encodePoint((ECPublicKey) kp.getPublic()));
    String priv = WebPushCrypto.b64(WebPushCrypto.encodeScalar((ECPrivateKey) kp.getPrivate()));
    VapidKeys.Pair p = keys(withVapid(pub, priv, "mailto:owner@example.com")).current().orElseThrow();
    assertThat(p.source()).isEqualTo("configuration");
    assertThat(p.publicKeyB64()).isEqualTo(pub);
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM push_vapid_key").query(Integer.class).single())
        .as("nothing generated").isZero();
    // a mismatched pair, garbage, or only one half: push is off, nothing throws
    String otherPriv = WebPushCrypto.b64(WebPushCrypto.encodeScalar(
        (ECPrivateKey) WebPushCrypto.generateKeyPair().getPrivate()));
    VapidKeys mismatched = keys(withVapid(pub, otherPriv, ""));
    assertThat(mismatched.current()).isEmpty();
    assertThat(mismatched.status()).contains("do not match");
    assertThat(keys(withVapid("not-a-key", priv, "")).current()).isEmpty();
    VapidKeys half = keys(withVapid(pub, "", ""));
    assertThat(half.current()).isEmpty();
    assertThat(half.status()).contains("only one");
  }

  @Test
  void disabledMeansUnavailable() {
    PushProperties d = PushProperties.defaults();
    PushProperties off = new PushProperties(false, d.vapid(), d.allowedEndpointHosts(), false, false, 4, 1000,
        d.connectTimeout(), d.requestTimeout(), 3, 10);
    VapidKeys k = keys(off);
    assertThat(k.current()).isEmpty();
    assertThat(k.status()).contains("disabled");
  }

  @Test
  void theSubjectIsConfiguredOrThePublicHttpsUrl() {
    assertThat(VapidKeys.subject("mailto:me@example.com", "https://stocks.gehan.cloud"))
        .isEqualTo("mailto:me@example.com");
    assertThat(VapidKeys.subject("", "https://stocks.gehan.cloud/")).isEqualTo("https://stocks.gehan.cloud");
    assertThat(VapidKeys.subject(null, "http://localhost:5173")).isEqualTo("mailto:admin@localhost");
  }

  @Test
  void tokensAreCachedPerAudienceUntilAnHourIsLeft() {
    VapidKeys k = keys(PushProperties.defaults());
    URI fcm = URI.create("https://fcm.googleapis.com/fcm/send/a");
    String first = k.authorization(fcm);
    assertThat(k.authorization(URI.create("https://fcm.googleapis.com/fcm/send/b"))).isEqualTo(first);
    assertThat(k.authorization(URI.create("https://web.push.apple.com/x"))).isNotEqualTo(first);
    time.advance(Duration.ofHours(10).plusMinutes(59));
    assertThat(k.authorization(fcm)).isEqualTo(first);
    time.advance(Duration.ofMinutes(2));                         // less than an hour left: re-signed
    assertThat(k.authorization(fcm)).isNotEqualTo(first);
  }

  String storedPrivate() {
    return db.jdbc().sql("SELECT private_key FROM push_vapid_key WHERE id = 1").query(String.class).single();
  }

  /**
   * Review finding: the private key was stored in plain text next to every subscription's secrets, so a database dump
   * alone could push to every device. It is now sealed under the pepper (like the OAuth signing key).
   */
  @Test
  void theStoredPrivateKeyIsEncryptedUnderThePepper() {
    VapidKeys.Pair a = keys(PushProperties.defaults()).current().orElseThrow();
    String stored = storedPrivate();
    assertThat(stored).startsWith("v1:").hasSizeLessThanOrEqualTo(100);
    assertThat(stored).doesNotContain(WebPushCrypto.b64(WebPushCrypto.encodeScalar(a.privateKey())));
    // the next start decrypts it: same pair
    assertThat(keys(PushProperties.defaults()).current().orElseThrow().publicKeyB64()).isEqualTo(a.publicKeyB64());
  }

  @Test
  void aPlainTextKeyFromAnEarlierVersionIsKeptAndRewrittenEncrypted() {
    KeyPair kp = WebPushCrypto.generateKeyPair();
    String pub = WebPushCrypto.b64(WebPushCrypto.encodePoint((ECPublicKey) kp.getPublic()));
    String priv = WebPushCrypto.b64(WebPushCrypto.encodeScalar((ECPrivateKey) kp.getPrivate()));
    db.jdbc().sql("INSERT INTO push_vapid_key (id, public_key, private_key, created_at) VALUES (1, ?, ?, ?)")
        .params(pub, priv, com.stocks.common.jdbc.Jdbc.ts(time.now())).update();
    VapidKeys.Pair p = keys(PushProperties.defaults()).current().orElseThrow();
    assertThat(p.publicKeyB64()).as("subscriptions stay bound to it").isEqualTo(pub);
    assertThat(storedPrivate()).startsWith("v1:");
    assertThat(keys(PushProperties.defaults()).current().orElseThrow().publicKeyB64()).isEqualTo(pub);
  }

  @Test
  void anotherPepperGetsANewPairAndTheOldSubscriptionsGo() {
    VapidKeys.Pair a = keys(PushProperties.defaults()).current().orElseThrow();
    com.stocks.testkit.Fixtures fx = new com.stocks.testkit.Fixtures(db.jdbc(), time);
    com.stocks.testkit.Fixtures.PlayerRef alice = fx.human("vapidalice");
    db.jdbc().sql("""
            INSERT INTO push_subscription (public_id, player_id, endpoint, endpoint_hash, p256dh, auth_secret,
                                           device_label, failure_count, created_at, updated_at)
            VALUES ('psb_1', ?, 'https://fcm.googleapis.com/fcm/send/x', 'h1', 'p', 'a', 'Chrome', 0, ?, ?)""")
        .params(alice.id(), com.stocks.common.jdbc.Jdbc.ts(time.now()), com.stocks.common.jdbc.Jdbc.ts(time.now()))
        .update();
    VapidKeys other = new VapidKeys(db.jdbc(), time, PushProperties.defaults(),
        TestProperties.stocks("stocks.security.api-key-pepper", "another-pepper-not-secret-0123456789abcd"));
    VapidKeys.Pair b = other.current().orElseThrow();
    assertThat(b.publicKeyB64()).isNotEqualTo(a.publicKeyB64());
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM push_subscription").query(Integer.class).single()).isZero();
    assertThat(storedPrivate()).startsWith("v1:");
  }

  @Test
  void withoutAPepperTheKeyLivesInMemoryOnly() {
    VapidKeys k = new VapidKeys(db.jdbc(), time, PushProperties.defaults(),
        TestProperties.stocks("stocks.security.api-key-pepper", ""));
    assertThat(k.current().orElseThrow().source()).isEqualTo("memory");
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM push_vapid_key").query(Integer.class).single()).isZero();
  }
}
