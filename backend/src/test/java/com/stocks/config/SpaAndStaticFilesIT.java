package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.stocks.testkit.TestClockConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * §24.3/§24.4: the SPA is served from the static locations with {@code index.html} as the fallback for client-side
 * routes (no extension, not under a server prefix), Vite-hashed {@code /assets/**} are immutable, {@code index.html}
 * is {@code no-cache}, and server paths never fall back to the SPA. Uses a temporary "built SPA" directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class SpaAndStaticFilesIT {

  static final Path DIST;

  static {
    try {
      DIST = Files.createTempDirectory("stocks-dist");
      Files.writeString(DIST.resolve("index.html"), "<!doctype html><title>stocks</title><div id=root></div>");
      Files.createDirectories(DIST.resolve("assets"));
      Files.writeString(DIST.resolve("assets/index-3f2a1b.js"), "console.log('app')");
      Files.writeString(DIST.resolve("manifest.webmanifest"), "{}");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void staticLocation(DynamicPropertyRegistry registry) {
    registry.add("spring.web.resources.static-locations", () -> DIST.toUri().toString());
  }

  @Autowired MockMvc mvc;
  @Autowired @Qualifier("extraMimeMappings")
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> extraMimeMappings;

  MockHttpServletResponse fetch(String path) throws Exception {
    return mvc.perform(get(path)).andReturn().getResponse();
  }

  @Test
  void clientSideRoutesFallBackToIndexHtml() throws Exception {
    // "/" is Boot's welcome page: a forward to index.html (MockMvc records forwards instead of following them)
    assertThat(fetch("/").getForwardedUrl()).isEqualTo("index.html");
    // review regression: symbols with a dot (BRK.B, BF.B) are routes; a reload of /stock/BRK.B used to be a JSON 404
    for (String path : new String[] {"/index.html", "/portfolio", "/quote/AAPL", "/admin/players/alice",
        "/stock/BRK.B", "/stock/BF.B"}) {
      MockHttpServletResponse r = fetch(path);
      assertThat(r.getStatus()).as(path).isEqualTo(200);
      assertThat(r.getContentAsString()).as(path).contains("<div id=root>");
      assertThat(r.getHeader("Cache-Control")).as(path).isEqualTo("no-cache");
      assertThat(r.getHeader("Content-Security-Policy")).isEqualTo(com.stocks.security.SecurityConfig.CONTENT_SECURITY_POLICY);
    }
  }

  @Test
  void hashedAssetsAreImmutable() throws Exception {
    MockHttpServletResponse r = fetch("/assets/index-3f2a1b.js");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getHeader("Cache-Control")).isEqualTo("max-age=31536000, public, immutable");
    assertThat(fetch("/manifest.webmanifest").getStatus()).isEqualTo(200);
  }

  @Test
  void theEmbeddedServerServesTheManifestAsManifestJson() {
    // MockMvc's servlet context ignores the container's MIME table, so check the customizer on a real factory
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    assertThat(factory.getSettings().getMimeMappings().get("webmanifest")).isNull();   // Tomcat's own table lacks it
    extraMimeMappings.customize(factory);
    assertThat(factory.getSettings().getMimeMappings().get("webmanifest")).isEqualTo("application/manifest+json");
    assertThat(factory.getSettings().getMimeMappings().get("json")).isEqualTo("application/json");   // defaults kept
  }

  @Test
  void missingFilesAndServerPathsNeverFallBack() throws Exception {
    for (String path : new String[] {"/assets/missing.js", "/missing.png", "/.well-known/openid-configuration",
        "/oauth2/nothing-here", "/h2-console/x"}) {
      MockHttpServletResponse r = fetch(path);
      assertThat(r.getStatus()).as(path).isEqualTo(404);
      assertThat(r.getContentAsString()).as(path).doesNotContain("<div id=root>");
    }
    MockHttpServletResponse api = mvc.perform(get("/api/v1/nope").with(user("u"))).andReturn().getResponse();
    assertThat(api.getStatus()).isEqualTo(404);
    assertThat(api.getContentType()).startsWith("application/problem+json");
    assertThat(api.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(fetch("/ws").getStatus()).as("/ws needs a session").isEqualTo(401);
    assertThat(fetch("/v3/api-docs").getStatus()).isEqualTo(401);
    // Phase 2 OAuth (§17.9): /oauth2/authorize is the server's, never the SPA's (a bad request goes to /oauth/error)
    MockHttpServletResponse authorize = fetch("/oauth2/authorize");
    assertThat(authorize.getStatus()).isEqualTo(302);
    assertThat(authorize.getHeader("Location")).startsWith("/oauth/error?error=invalid_request");
  }

  @Test
  void spaRouteDetection() {
    assertThat(WebConfig.isSpaRoute("")).isTrue();
    assertThat(WebConfig.isSpaRoute("league/week")).isTrue();
    assertThat(WebConfig.isSpaRoute("/apiary")).isTrue();
    assertThat(WebConfig.isSpaRoute("api/v1/me")).isFalse();
    assertThat(WebConfig.isSpaRoute("mcp")).isFalse();
    assertThat(WebConfig.isSpaRoute("swagger-ui/index.html")).isFalse();
    assertThat(WebConfig.isSpaRoute("logo.png")).isFalse();
    assertThat(WebConfig.isSpaRoute("stock/BRK.B")).isTrue();
    assertThat(WebConfig.isSpaRoute("/stock/BF.B")).isTrue();
    assertThat(WebConfig.isSpaRoute("sw.js")).isFalse();
    assertThat(WebConfig.isSpaRoute("assets/font.WOFF2")).isFalse();
  }
}
