package com.stocks.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.oauth.cimd.CimdRulesTest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure rules of §17.9: redirect URIs, resource matching, PKCE, the signing-key file and encryption, limits. */
class OAuthUnitTest {

  private final RedirectUris redirects = new RedirectUris(CimdRulesTest.PROPS);

  @Test
  void redirectAllowlist() {
    assertThat(redirects.isAllowed("https://claude.ai/api/mcp/auth_callback")).isTrue();
    assertThat(redirects.isAllowed("https://chatgpt.com/connector_platform_oauth_redirect")).isTrue();
    assertThat(redirects.isAllowed("https://chatgpt.com/connector/oauth/abc_123-X")).isTrue();
    assertThat(redirects.isAllowed("https://chatgpt.com/connector/oauth/a/b")).isFalse();
    assertThat(redirects.isAllowed("https://chatgpt.com/connector/oauth/")).isFalse();
    assertThat(redirects.isAllowed("https://claude.ai/api/mcp/auth_callback/../x")).isFalse();
    assertThat(redirects.isAllowed("https://claude.ai.evil.example/api/mcp/auth_callback")).isFalse();
    assertThat(redirects.isAllowed("https://claude.ai/api/mcp/auth_callback#x")).isFalse();
    assertThat(redirects.isAllowed("http://claude.ai/api/mcp/auth_callback")).isFalse();
    assertThat(redirects.isAllowed("http://localhost/callback")).isTrue();
    assertThat(redirects.isAllowed("http://127.0.0.1:33418/callback")).isTrue();
    assertThat(redirects.isAllowed("http://[::1]:8080/cb")).isTrue();
    assertThat(redirects.isAllowed("http://192.168.1.2/cb")).isFalse();
    assertThat(redirects.isAllowed("http://user@localhost/cb")).isFalse();
    assertThat(new RedirectUris(List.of(), false).isAllowed("http://localhost/cb")).isFalse();
  }

  @Test
  void loopbackRedirectsMatchWithThePortIgnored() {
    assertThat(RedirectUris.matches("http://localhost/callback", "http://localhost:3118/callback")).isTrue();
    assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:50000/callback")).isTrue();
    assertThat(RedirectUris.matches("http://localhost/callback", "http://localhost:3118/other")).isFalse();
    assertThat(RedirectUris.matches("http://localhost/callback", "http://127.0.0.1:3118/callback")).isFalse();
    assertThat(RedirectUris.matches("https://claude.ai/api/mcp/auth_callback",
        "https://claude.ai:444/api/mcp/auth_callback")).isFalse();
    assertThat(RedirectUris.matches("https://claude.ai/api/mcp/auth_callback",
        "https://claude.ai/api/mcp/auth_callback")).isTrue();
    assertThat(RedirectUris.host("https://claude.ai/api/mcp/auth_callback")).isEqualTo("claude.ai");
    assertThat(RedirectUris.isLoopback("http://127.0.0.1:1/cb")).isTrue();
  }

  @Test
  void resourceAndIssuerComeFromThePublicUrl() {
    OAuthUrls urls = new OAuthUrls("https://stocks.gehan.cloud/");
    assertThat(urls.issuer()).isEqualTo("https://stocks.gehan.cloud");
    assertThat(urls.resource()).isEqualTo("https://stocks.gehan.cloud/mcp");
    assertThat(urls.protectedResourceMetadata())
        .isEqualTo("https://stocks.gehan.cloud/.well-known/oauth-protected-resource/mcp");
    assertThat(urls.isResource("https://stocks.gehan.cloud/mcp")).isTrue();
    assertThat(urls.isResource("HTTPS://STOCKS.GEHAN.CLOUD/mcp/")).isTrue();
    assertThat(urls.isResource("https://stocks.gehan.cloud:443/mcp")).isTrue();
    assertThat(urls.isResource("https://stocks.gehan.cloud/MCP")).isFalse();
    assertThat(urls.isResource("https://stocks.gehan.cloud/mcp#x")).isFalse();
    assertThat(urls.isResource("https://stocks.gehan.cloud")).isFalse();
    assertThat(urls.isResource("https://evil.example/mcp")).isFalse();
  }

  @Test
  void pkceS256() {
    // RFC 7636 Appendix B
    assertThat(Secrets.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    assertThat(Secrets.PKCE.matcher("a".repeat(42)).matches()).isFalse();
    assertThat(Secrets.PKCE.matcher("a".repeat(43)).matches()).isTrue();
    assertThat(Secrets.PKCE.matcher("a".repeat(129)).matches()).isFalse();
    assertThat(Secrets.random()).hasSize(43);
    assertThat(new OAuthIds(new MutableTimeSource(Instant.parse("2026-10-09T14:00:00Z"))).request())
        .startsWith("oar_").hasSize(36);
  }

  @Test
  void refreshMayNarrowScopes() {
    Set<String> granted = Set.of("market:read", "account:read");
    assertThat(TokenEndpoint.narrow(granted, null)).isEqualTo(granted);
    assertThat(TokenEndpoint.narrow(granted, "market:read offline_access")).containsExactly("market:read");
    assertThatThrownBy(() -> TokenEndpoint.narrow(granted, "trade")).isInstanceOf(OAuthException.class)
        .extracting(e -> ((OAuthException) e).error()).isEqualTo(OAuthException.INVALID_SCOPE);
  }

  @Test
  void dcrCredentialsFromBasicOrTheForm() {
    String basic = "Basic " + Base64.getEncoder().encodeToString("ocl_1:s%3Acret".getBytes(StandardCharsets.UTF_8));
    OAuthClients.Credentials c = OAuthClients.credentials(basic, null, null);
    assertThat(c.clientId()).isEqualTo("ocl_1");
    assertThat(c.secret()).isEqualTo("s:cret");
    assertThat(c.basic()).isTrue();
    assertThat(OAuthClients.credentials(null, "ocl_2", "x").secret()).isEqualTo("x");
    assertThatThrownBy(() -> OAuthClients.credentials(basic, "ocl_other", null)).isInstanceOf(OAuthException.class);
    assertThatThrownBy(() -> OAuthClients.credentials("Basic %%%", null, null)).isInstanceOf(OAuthException.class);
  }

  @Test
  void perIpWindows() {
    MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-09T14:00:00Z"));
    OAuthProperties tight = new OAuthProperties(true, null, Duration.ofMinutes(30), Duration.ofDays(30),
        Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(60), true,
        List.of(), 2, CimdRulesTest.PROPS.cimd(),
        new OAuthProperties.Dcr(true, 500, 1, Duration.ofHours(24), Duration.ofDays(90)));
    OAuthRateLimiter limiter = new OAuthRateLimiter(time, tight);
    limiter.charge(OAuthRateLimiter.Bucket.TOKEN, "1.2.3.4");
    limiter.charge(OAuthRateLimiter.Bucket.TOKEN, "1.2.3.4");
    assertThatThrownBy(() -> limiter.charge(OAuthRateLimiter.Bucket.TOKEN, "1.2.3.4"))
        .isInstanceOf(OAuthException.class).extracting(e -> ((OAuthException) e).status()).isEqualTo(429);
    limiter.charge(OAuthRateLimiter.Bucket.TOKEN, "5.6.7.8");
    limiter.charge(OAuthRateLimiter.Bucket.AUTHORIZE, "1.2.3.4");
    limiter.charge(OAuthRateLimiter.Bucket.REGISTER, "1.2.3.4");
    assertThatThrownBy(() -> limiter.charge(OAuthRateLimiter.Bucket.REGISTER, "1.2.3.4"))
        .isInstanceOf(OAuthException.class);
    time.advance(Duration.ofMinutes(1));
    limiter.charge(OAuthRateLimiter.Bucket.TOKEN, "1.2.3.4");
    assertThatThrownBy(() -> limiter.charge(OAuthRateLimiter.Bucket.REGISTER, "1.2.3.4"))
        .as("registrations are per hour").isInstanceOf(OAuthException.class);
    time.advance(Duration.ofHours(1));
    limiter.charge(OAuthRateLimiter.Bucket.REGISTER, "1.2.3.4");
  }

  // ------------------------------------------------------------------------------------------- signing key

  static RSAPrivateCrtKey newKey(int bits) throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(bits);
    return (RSAPrivateCrtKey) g.generateKeyPair().getPrivate();
  }

  static String pem(String type, byte[] der) {
    return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(der) + "\n-----END " + type + "-----\n";
  }

  /** The PKCS#1 body of a PKCS#8 RSA key: the content of its trailing OCTET STRING. */
  static byte[] pkcs1(byte[] pkcs8) {
    // SEQUENCE { INTEGER 0, SEQUENCE { OID, NULL }, OCTET STRING { pkcs1 } }: skip to the OCTET STRING (tag 0x04)
    int i = 2 + ((pkcs8[1] & 0x80) != 0 ? (pkcs8[1] & 0x7f) : 0);   // after the outer header
    i += 3;                                                          // INTEGER 0
    i += 2 + (pkcs8[i + 1] & 0xff);                                  // AlgorithmIdentifier
    int lenByte = pkcs8[i + 1] & 0xff;
    int headerLen = 2 + ((lenByte & 0x80) != 0 ? (lenByte & 0x7f) : 0);
    return Arrays.copyOfRange(pkcs8, i + headerLen, pkcs8.length);
  }

  @Test
  void pemFilesInPkcs8AndPkcs1() throws Exception {
    RSAPrivateCrtKey key = newKey(2048);
    RSAKey fromPkcs8 = SigningKeys.toJwk(SigningKeys.parsePem(pem("PRIVATE KEY", key.getEncoded())));
    RSAKey fromPkcs1 = SigningKeys.toJwk(SigningKeys.parsePem(pem("RSA PRIVATE KEY", pkcs1(key.getEncoded()))));
    assertThat(fromPkcs1.getKeyID()).isEqualTo(fromPkcs8.getKeyID());
    assertThat(fromPkcs8.toRSAPrivateKey().getModulus()).isEqualTo(key.getModulus());
    assertThatThrownBy(() -> SigningKeys.toJwk(SigningKeys.parsePem(pem("PRIVATE KEY", newKey(1024).getEncoded()))))
        .hasMessageContaining("2048");
    assertThatThrownBy(() -> SigningKeys.parsePem("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storedKeysAreEncryptedUnderThePepper() throws Exception {
    byte[] k1 = SigningKeys.hkdf("pepper-one".getBytes(StandardCharsets.UTF_8));
    byte[] k2 = SigningKeys.hkdf("pepper-two".getBytes(StandardCharsets.UTF_8));
    assertThat(k1).hasSize(32).isEqualTo(SigningKeys.hkdf("pepper-one".getBytes(StandardCharsets.UTF_8)));
    byte[] secret = "pkcs8 bytes".getBytes(StandardCharsets.UTF_8);
    String sealed = SigningKeys.encrypt(k1, "kid-1", secret);
    assertThat(new String(Base64.getDecoder().decode(sealed), StandardCharsets.ISO_8859_1)).doesNotContain("pkcs8");
    assertThat(SigningKeys.decrypt(k1, "kid-1", sealed)).isEqualTo(secret);
    assertThatThrownBy(() -> SigningKeys.decrypt(k2, "kid-1", sealed)).isInstanceOf(GeneralSecurityException.class);
    assertThatThrownBy(() -> SigningKeys.decrypt(k1, "kid-2", sealed)).as("the kid is bound as AAD")
        .isInstanceOf(GeneralSecurityException.class);
  }
}
