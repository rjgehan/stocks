package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.StocksApplication;
import com.stocks.testkit.TestClockConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §24.3 on a real Tomcat (random port): {@code CF-Connecting-IP} and {@code X-Forwarded-Proto} are honored only when
 * the socket peer is a trusted proxy ({@code server.tomcat.remoteip.internal-proxies}, set from
 * {@code TRUSTED_PROXIES}, either the prod default regex or a CIDR list). The client connects from 127.0.0.1: trusted
 * under the prod default and under a CIDR list with loopback, untrusted under a CIDR list without it. Also checks that
 * HSTS is sent only on a (forwarded) secure request.
 */
class ForwardedHeadersIT {

  /** The default of {@code TRUSTED_PROXIES} in application-prod.yml (a regex). */
  static final String PROD_DEFAULT = "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|"
      + "192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3}|0:0:0:0:0:0:0:1|::1";
  /** The same networks as a CIDR list (the CasaOS case: TRUSTED_PROXIES=<cloudflared's Docker network>). */
  static final String CIDRS = "127.0.0.0/8, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16";
  static final Map<String, String> FORWARDED = Map.of("CF-Connecting-IP", "203.0.113.7", "X-Forwarded-Proto", "https");

  @TestConfiguration(proxyBeanMethods = false)
  @Import(TestClockConfig.class)
  static class Echo {
    /** A public (chain 4) echo of what the app sees after the valve. */
    @TestComponent
    @RestController
    static class EchoController {
      @GetMapping("/__test/echo")
      Map<String, Object> echo(HttpServletRequest request) {
        return Map.of("ip", ClientIp.resolve(request), "secure", request.isSecure(), "scheme", request.getScheme());
      }
    }
  }

  static String get(int port, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__test/echo"));
    headers.forEach(b::header);
    HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return r.body() + " hsts=" + r.headers().firstValue("Strict-Transport-Security").orElse("-");
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      classes = {StocksApplication.class, Echo.class},
      properties = {"server.forward-headers-strategy=native",
          "server.tomcat.remoteip.remote-ip-header=cf-connecting-ip",
          "server.tomcat.remoteip.protocol-header=x-forwarded-proto",
          "management.server.port=-1"})
  @ActiveProfiles("test")
  class TrustedPeer {
    @LocalServerPort int port;

    /** Dynamic, not inlined: inlined test properties would treat the regex backslashes as escapes. */
    @DynamicPropertySource
    static void prodDefault(DynamicPropertyRegistry registry) {
      registry.add("server.tomcat.remoteip.internal-proxies", () -> PROD_DEFAULT);
    }

    @Test
    void headersFromATrustedProxyAreHonored() throws Exception {
      String echo = get(port, FORWARDED);
      assertThat(echo).contains("\"ip\":\"203.0.113.7\"").contains("\"secure\":true").contains("\"scheme\":\"https\"")
          .contains("hsts=max-age=");
    }

    @Test
    void withoutHeadersTheSocketPeerIsUsed() throws Exception {
      assertThat(get(port, Map.of())).contains("\"ip\":\"127.0.0.1\"").contains("\"secure\":false").contains("hsts=-");
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      classes = {StocksApplication.class, Echo.class},
      properties = {"server.forward-headers-strategy=native",
          "server.tomcat.remoteip.remote-ip-header=cf-connecting-ip",
          "server.tomcat.remoteip.protocol-header=x-forwarded-proto",
          "server.tomcat.remoteip.internal-proxies=" + CIDRS,
          "management.server.port=-1"})
  @ActiveProfiles("test")
  class TrustedCidrPeer {
    @LocalServerPort int port;

    @Test
    void aCidrListIsHonoredToo() throws Exception {
      assertThat(get(port, FORWARDED)).contains("\"ip\":\"203.0.113.7\"").contains("\"secure\":true");
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      classes = {StocksApplication.class, Echo.class},
      properties = {"server.forward-headers-strategy=native",
          "server.tomcat.remoteip.remote-ip-header=cf-connecting-ip",
          "server.tomcat.remoteip.protocol-header=x-forwarded-proto",
          "server.tomcat.remoteip.internal-proxies=10.0.0.0/8, 172.16.0.0/12",
          "management.server.port=-1"})
  @ActiveProfiles("test")
  class UntrustedPeer {
    @LocalServerPort int port;

    @Test
    void headersFromAnUntrustedPeerAreIgnored() throws Exception {
      assertThat(get(port, FORWARDED)).contains("\"ip\":\"127.0.0.1\"").contains("\"secure\":false")
          .doesNotContain("203.0.113.7").contains("hsts=-");
    }
  }
}
