package com.stocks.oauth.cimd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.oauth.OAuthClient;
import com.stocks.oauth.OAuthException;
import com.stocks.oauth.OAuthProperties;
import com.stocks.oauth.RedirectUris;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * §17.9.2 rules for Client ID Metadata Documents: the client-identifier URL, the address policy, the document
 * validation (the real claude.ai and ChatGPT documents included) and the cache lifetime.
 */
public class CimdRulesTest {

  public static final OAuthProperties PROPS = new OAuthProperties(true, null, Duration.ofMinutes(30), Duration.ofDays(30),
      Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(60), true,
      List.of("https://claude.ai/api/mcp/auth_callback", "https://chatgpt.com/connector_platform_oauth_redirect",
          "https://chatgpt.com/connector/oauth/*"), 60,
      new OAuthProperties.Cimd(true, Duration.ofSeconds(3), 8192, Duration.ofMinutes(5), Duration.ofHours(24)),
      new OAuthProperties.Dcr(true, 500, 20, Duration.ofHours(24), Duration.ofDays(90)));

  static final String CLAUDE = "https://claude.ai/oauth/mcp-oauth-client-metadata";
  static final String CHATGPT = "https://chatgpt.com/oauth/client.json";

  @ParameterizedTest
  @ValueSource(strings = {"http://claude.ai/x", "https://claude.ai", "https://claude.ai/", "https://u:p@claude.ai/x",
      "https://claude.ai/x#f", "https://claude.ai:8443/x", "https://claude.ai/a/../b", "https://claude.ai/a/%2e%2e/b",
      "https://claude.ai/./x", "not a url", ""})
  void badClientIdUrls(String url) {
    assertThat(ClientIdUrls.problem(url)).as(url).isNotNull();
  }

  @Test
  void goodClientIdUrls() {
    assertThat(ClientIdUrls.problem(CLAUDE)).isNull();
    assertThat(ClientIdUrls.problem("https://chatgpt.com/oauth/abc123/client.json")).isNull();
    assertThat(ClientIdUrls.problem("https://claude.ai:443/oauth/x")).isNull();
    assertThat(ClientIdUrls.problem("https://x.example/" + "a".repeat(600))).isNotNull();
    assertThat(ClientIdUrls.looksLikeUrl(CLAUDE)).isTrue();
    assertThat(ClientIdUrls.looksLikeUrl("ocl_01J")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1",
      "100.127.255.255", "169.254.169.254", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.2.1",
      "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "::ffff:192.168.1.1", "::ffff:127.0.0.1",
      "64:ff9b::a00:1", "2002:c0a8:101::1", "2001:db8::1", "::127.0.0.1"})
  void privateAddressesAreNotPublic(String address) throws Exception {
    assertThat(AddressPolicy.isPublic(InetAddress.getByName(address))).as(address).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"93.184.216.34", "1.1.1.1", "160.79.104.10", "172.32.0.1", "100.128.0.1",
      "2606:4700::6810:84e5", "::ffff:93.184.216.34", "2002:5db8:d822::1"})
  void publicAddresses(String address) throws Exception {
    assertThat(AddressPolicy.isPublic(InetAddress.getByName(address))).as(address).isTrue();
  }

  // ------------------------------------------------------------------------------------------- documents

  private final MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-09T14:00:00Z"));
  private final RedirectUris redirects = new RedirectUris(PROPS);

  private CimdClients clients(CimdFetcher fetcher) {
    return new CimdClients(fetcher, JacksonConfig.newMapper(), redirects, PROPS, time);
  }

  private static CimdFetcher serving(String doc, String cacheControl, AtomicInteger counter) {
    return url -> {
      counter.incrementAndGet();
      return new CimdFetcher.Response(doc.getBytes(StandardCharsets.UTF_8), cacheControl);
    };
  }

  @Test
  void claudesRealDocument() {
    String doc = "{\"client_id\":\"https://claude.ai/oauth/mcp-oauth-client-metadata\",\"client_name\":\"Claude\","
        + "\"client_uri\":\"https://claude.ai\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"],"
        + "\"grant_types\":[\"authorization_code\",\"refresh_token\",\"urn:ietf:params:oauth:grant-type:jwt-bearer\"],"
        + "\"response_types\":[\"code\"],\"token_endpoint_auth_method\":\"none\"}";
    OAuthClient c = clients(serving(doc, null, new AtomicInteger())).resolve(CLAUDE);
    assertThat(c.kind()).isEqualTo(OAuthClientKind.CIMD);
    assertThat(c.name()).isEqualTo("Claude");
    assertThat(c.host()).isEqualTo("claude.ai");
    assertThat(c.redirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");
    assertThat(c.grantTypes()).containsExactlyInAnyOrder("authorization_code", "refresh_token");
    assertThat(c.isPublic()).isTrue();
    assertThat(c.allowsRefresh()).isTrue();
  }

  @Test
  void chatgptsRealDocumentIsAPublicClient() {
    String doc = "{\"client_id\":\"https://chatgpt.com/oauth/client.json\",\"client_uri\":\"https://chatgpt.com/\","
        + "\"redirect_uris\":[\"https://chatgpt.com/connector_platform_oauth_redirect\"],"
        + "\"token_endpoint_auth_method\":\"private_key_jwt\",\"token_endpoint_auth_methods_supported\":[\"none\","
        + "\"private_key_jwt\"],\"grant_types\":[\"authorization_code\",\"refresh_token\"],\"response_types\":[\"code\"],"
        + "\"client_name\":\"ChatGPT\",\"token_endpoint_auth_signing_alg\":\"RS256\","
        + "\"jwks_uri\":\"https://chatgpt.com/oauth/jwks.json\"}";
    OAuthClient c = clients(serving(doc, null, new AtomicInteger())).resolve(CHATGPT);
    assertThat(c.name()).isEqualTo("ChatGPT");
    assertThat(c.isPublic()).isTrue();
  }

  @Test
  void invalidDocuments() {
    String base = "{\"client_id\":\"" + CLAUDE + "\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"]";
    List<String> bad = List.of(
        "[1,2]",
        "{\"client_id\":\"https://claude.ai/other\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"]}",
        base + ",\"client_secret\":\"s\"}",
        base + ",\"token_endpoint_auth_method\":\"client_secret_basic\"}",
        "{\"client_id\":\"" + CLAUDE + "\",\"redirect_uris\":[\"https://evil.example/cb\"]}",
        "{\"client_id\":\"" + CLAUDE + "\",\"redirect_uris\":[]}",
        base + ",\"grant_types\":[\"refresh_token\"]}",
        base + ",\"response_types\":[\"token\"]}",
        "not json");
    for (String doc : bad) {
      assertThatThrownBy(() -> clients(serving(doc, null, new AtomicInteger())).resolve(CLAUDE)).as(doc)
          .isInstanceOf(OAuthException.class).extracting(e -> ((OAuthException) e).error())
          .isEqualTo(OAuthException.INVALID_CLIENT);
    }
    // a missing client_name shows the host; only allowed redirect URIs are kept
    OAuthClient c = clients(serving("{\"client_id\":\"" + CLAUDE + "\",\"redirect_uris\":[\"https://evil.example/cb\","
        + "\"https://claude.ai/api/mcp/auth_callback\"]}", null, new AtomicInteger())).resolve(CLAUDE);
    assertThat(c.name()).isEqualTo("claude.ai");
    assertThat(c.redirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");
    assertThat(c.allowsRefresh()).as("grant_types absent: both").isTrue();
  }

  @Test
  void validDocumentsAreCachedForTheClampedMaxAge() {
    String doc = "{\"client_id\":\"" + CLAUDE + "\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"]}";
    AtomicInteger fetches = new AtomicInteger();
    CimdClients c = clients(serving(doc, "public, max-age=60", fetches));
    c.resolve(CLAUDE);
    c.resolve(CLAUDE);
    assertThat(fetches.get()).isEqualTo(1);
    time.advance(Duration.ofMinutes(4));
    c.resolve(CLAUDE);
    assertThat(fetches.get()).as("max-age 60 s is raised to 5 min").isEqualTo(1);
    time.advance(Duration.ofMinutes(2));
    c.resolve(CLAUDE);
    assertThat(fetches.get()).isEqualTo(2);

    AtomicInteger noStore = new AtomicInteger();
    CimdClients n = clients(serving(doc, "no-store", noStore));
    n.resolve(CLAUDE);
    n.resolve(CLAUDE);
    assertThat(noStore.get()).isEqualTo(2);
    assertThat(n.cacheTtl("max-age=999999")).isEqualTo(Duration.ofHours(24));
    assertThat(n.cacheTtl(null)).isEqualTo(Duration.ofMinutes(5));
    assertThat(n.cacheTtl("max-age=3600")).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void fetchErrorsAreNeverCached() {
    AtomicInteger fetches = new AtomicInteger();
    CimdClients c = clients(url -> {
      fetches.incrementAndGet();
      throw new CimdFetcher.FetchException("The client metadata document answered HTTP 503.");
    });
    assertThatThrownBy(() -> c.resolve(CLAUDE)).hasMessageContaining("503");
    assertThatThrownBy(() -> c.resolve(CLAUDE)).hasMessageContaining("503");
    assertThat(fetches.get()).isEqualTo(2);
  }
}
