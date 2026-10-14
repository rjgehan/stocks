package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** §17.5 read and per-IP limits through the real chain 2 (tiny limits; the fixed test clock refills nothing). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
@TestPropertySource(properties = {
    "stocks.ratelimit.key-reads-per-min=6", "stocks.ratelimit.key-reads-burst=3",
    "stocks.ratelimit.web-reads-per-min=5", "stocks.ratelimit.heavy-reads-per-min-key=2",
    "stocks.ratelimit.heavy-reads-per-min-web=2", "stocks.ratelimit.login-per-min-ip=2",
    "stocks.ratelimit.signup-per-hour-ip=1", "stocks.ratelimit.anon-per-min-ip=4"})
class RateLimitIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;

  Fixtures fx;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
  }

  static String ip() {
    return "10.9.0." + SEQ.incrementAndGet();
  }

  static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder b, String ip) {
    return b.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    });
  }

  MvcResult perform(MockHttpServletRequestBuilder b) throws Exception {
    return mvc.perform(b).andReturn();
  }

  static String code(MvcResult r) {
    return JsonPath.read(WebSession.contentOf(r), "$.code");
  }

  /**
   * Adds a real {@code XSRF-TOKEN} cookie and header fetched from {@code ip} (spring-security-test's {@code csrf()}
   * would swap the shared CsrfFilter's repository for the rest of the context).
   */
  MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder b, String ip) throws Exception {
    MvcResult r = perform(from(get("/api/v1/auth/csrf"), ip));
    jakarta.servlet.http.Cookie token = r.getResponse().getCookie(WebSession.XSRF_COOKIE);
    return from(b, ip).cookie(token).header(WebSession.XSRF_HEADER, token.getValue());
  }

  @Test
  void aKeyGetsItsBurstThen429WithHeadersAndRefillsWithTime() throws Exception {
    Fixtures.PlayerRef bot = fx.ai("ratebot" + SEQ.incrementAndGet(), fx.human("rateowner" + SEQ.incrementAndGet()));
    String key = fx.apiKey(bot, properties.security().apiKeyPepper());
    for (int i = 0; i < 3; i++) {
      MvcResult ok = perform(get("/api/v1/me").header("Authorization", "Bearer " + key));
      assertThat(ok.getResponse().getStatus()).isEqualTo(200);
      assertThat(ok.getResponse().getHeader(RateLimitFilter.LIMIT)).isEqualTo("6");
      assertThat(ok.getResponse().getHeader(RateLimitFilter.REMAINING)).isEqualTo(Integer.toString(2 - i));
    }
    MvcResult limited = perform(get("/api/v1/me").header("Authorization", "Bearer " + key));
    assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    assertThat(code(limited)).isEqualTo("RATE_LIMITED");
    assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("10");
    assertThat((Integer) JsonPath.read(WebSession.contentOf(limited), "$.retryAfterS")).isEqualTo(10);
    assertThat(limited.getResponse().getHeader(RateLimitFilter.REMAINING)).isEqualTo("0");
    // a second key of the same player shares the bucket
    String second = fx.apiKey(bot, properties.security().apiKeyPepper());
    assertThat(perform(get("/api/v1/me").header("Authorization", "Bearer " + second)).getResponse().getStatus())
        .isEqualTo(429);

    time.advance(Duration.ofSeconds(10));
    assertThat(perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void heavyReadsHaveTheirOwnSmallerBucket() throws Exception {
    Fixtures.PlayerRef p = fx.human("heavy" + SEQ.incrementAndGet());
    String key = fx.apiKey(p, properties.security().apiKeyPepper());
    for (int i = 0; i < 2; i++) {
      assertThat(perform(get("/api/v1/leaderboard").header("Authorization", "Bearer " + key)).getResponse()
          .getStatus()).isNotEqualTo(429);
    }
    assertThat(perform(get("/api/v1/leaderboard").header("Authorization", "Bearer " + key)).getResponse()
        .getStatus()).isEqualTo(429);
    assertThat(perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).getResponse().getStatus())
        .as("ordinary reads still have tokens").isEqualTo(200);
  }

  /** Public mode (§16.7): a player's live portfolio, orders, activity and P&amp;L value accounts like a profile. */
  @Test
  void publicPortfolioReadsAreHeavy() throws Exception {
    for (String route : new String[] {"portfolio", "orders", "activities", "pnl-analysis", "pnl-calendar"}) {
      Fixtures.PlayerRef p = fx.human("pubheavy" + SEQ.incrementAndGet());
      String key = fx.apiKey(p, properties.security().apiKeyPepper());
      MvcResult r = perform(get("/api/v1/players/" + p.handle() + "/" + route)
          .header("Authorization", "Bearer " + key));
      assertThat(r.getResponse().getHeader(RateLimitFilter.LIMIT)).as(route).isEqualTo("2");
    }
  }

  @Test
  void orderWritesAreLeftToOrderGate() throws Exception {
    Fixtures.PlayerRef p = fx.human("writer" + SEQ.incrementAndGet());
    String key = fx.apiKey(p, properties.security().apiKeyPepper());
    for (int i = 0; i < 3; i++) {
      perform(get("/api/v1/me").header("Authorization", "Bearer " + key));
    }
    assertThat(perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).getResponse().getStatus())
        .isEqualTo(429);
    MvcResult order = perform(post("/api/v1/orders").header("Authorization", "Bearer " + key)
        .contentType(MediaType.APPLICATION_JSON).content("{}"));
    assertThat(order.getResponse().getStatus()).as("not read-limited").isNotEqualTo(429);
    assertThat(order.getResponse().getHeader(RateLimitFilter.LIMIT)).isNull();
  }

  @Test
  void webSessionsHaveTheirOwnBucketAndAdminIsNeverLimited() {
    Fixtures.PlayerRef admin = fx.admin("rateadmin" + SEQ.incrementAndGet());
    WebSession s = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    int ok = 0;
    for (int i = 0; i < 8; i++) {
      if (s.perform(get("/api/v1/me")).getResponse().getStatus() == 200) {
        ok++;
      }
    }
    assertThat(ok).isEqualTo(5);
    for (int i = 0; i < 10; i++) {
      assertThat(s.perform(get("/api/v1/admin/settings")).getResponse().getStatus()).isEqualTo(200);
    }
  }

  @Test
  void loginIsLimitedPerIp() throws Exception {
    String ip = ip();
    String body = "{\"handle\":\"nobody_here\",\"password\":\"whatever-password\"}";
    for (int i = 0; i < 2; i++) {
      MvcResult r = perform(csrf(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
          .content(body));
      assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
    MvcResult limited = perform(csrf(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
        .content(body));
    assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    assertThat(code(limited)).isEqualTo("RATE_LIMITED");
    MvcResult otherIp = perform(csrf(post("/api/v1/auth/login"), ip()).contentType(MediaType.APPLICATION_JSON)
        .content(body));
    assertThat(otherIp.getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void signupIsLimitedPerIpPerHour() throws Exception {
    String ip = ip();
    String body = "{\"inviteCode\":\"aaaaaaaaaaaaaaaaaaaa\",\"handle\":\"x1\",\"displayName\":\"Xx\","
        + "\"password\":\"long-enough-password\"}";
    MvcResult first = perform(csrf(post("/api/v1/auth/signup"), ip).contentType(MediaType.APPLICATION_JSON)
        .content(body));
    assertThat(code(first)).isEqualTo("INVITE_INVALID");
    MvcResult second = perform(csrf(post("/api/v1/auth/signup"), ip).contentType(MediaType.APPLICATION_JSON)
        .content(body));
    assertThat(code(second)).isEqualTo("RATE_LIMITED");
    assertThat(Long.parseLong(second.getResponse().getHeader("Retry-After"))).isBetween(3500L, 3600L);
  }

  @Test
  void anonymousCallersArePerIp() throws Exception {
    String ip = ip();
    for (int i = 0; i < 4; i++) {
      assertThat(perform(from(get("/api/v1/auth/csrf"), ip)).getResponse().getStatus()).isEqualTo(204);
    }
    assertThat(perform(from(get("/api/v1/auth/csrf"), ip)).getResponse().getStatus()).isEqualTo(429);
  }

  // ------------------------------------------------------------------------------------------- review regressions

  /** Requests are classified on the decoded path: an encoded {@code /auth/logi%6E} is still a login (10/min). */
  @Test
  void anEncodedLoginPathPaysTheLoginBucket() throws Exception {
    String ip = ip();
    String body = "{\"handle\":\"nobody_here\",\"password\":\"whatever-password\"}";
    for (int i = 0; i < 2; i++) {
      MvcResult r = perform(csrf(post(java.net.URI.create("/api/v1/auth/logi%6E")), ip)
          .contentType(MediaType.APPLICATION_JSON).content(body));
      assertThat(r.getResponse().getStatus()).as(WebSession.contentOf(r)).isEqualTo(401);
      assertThat(r.getResponse().getHeader(RateLimitFilter.LIMIT)).isEqualTo("2");
    }
    MvcResult limited = perform(csrf(post(java.net.URI.create("/api/v1/auth/logi%6E")), ip)
        .contentType(MediaType.APPLICATION_JSON).content(body));
    assertThat(code(limited)).isEqualTo("RATE_LIMITED");
  }

  /** A signed-in caller's login attempts are per IP too, not charged to the session's much larger read bucket. */
  @Test
  void aSignedInCallerStillPaysTheLoginBucket() throws Exception {
    String ip = ip();
    Fixtures.PlayerRef p = fx.human("signedin" + SEQ.incrementAndGet());
    WebSession s = WebSession.anonymous(mvc);
    s.perform(from(get("/api/v1/auth/csrf"), ip));
    MvcResult in = s.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
        .content("{\"handle\":\"" + p.handle() + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}"));
    assertThat(in.getResponse().getStatus()).isEqualTo(200);
    String guess = "{\"handle\":\"someone_else\",\"password\":\"guess-guess-1\"}";
    MvcResult first = s.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
        .content(guess));
    assertThat(first.getResponse().getStatus()).isEqualTo(401);
    assertThat(first.getResponse().getHeader(RateLimitFilter.LIMIT)).isEqualTo("2");
    MvcResult second = s.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
        .content(guess));
    assertThat(code(second)).isEqualTo("RATE_LIMITED");
  }

  /** Bad API keys cost the IP's anonymous budget; once it is gone, keys are refused before any lookup. */
  @Test
  void badApiKeysAreChargedToTheCallerIp() throws Exception {
    String ip = ip();
    for (int i = 0; i < 4; i++) {
      MvcResult r = perform(from(get("/api/v1/me"), ip).header("Authorization", "Bearer stk_aaaaaaaaaaaa_"
          + "x".repeat(40) + i));
      assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
    MvcResult refused = perform(from(get("/api/v1/me"), ip).header("Authorization", "Bearer stk_aaaaaaaaaaaa_"
        + "y".repeat(40)));
    assertThat(refused.getResponse().getStatus()).isEqualTo(429);
    assertThat(code(refused)).isEqualTo("RATE_LIMITED");
    // a valid key from another IP is unaffected
    Fixtures.PlayerRef p = fx.human("goodkey" + SEQ.incrementAndGet());
    String key = fx.apiKey(p, properties.security().apiKeyPepper());
    assertThat(perform(from(get("/api/v1/me"), ip()).header("Authorization", "Bearer " + key)).getResponse()
        .getStatus()).isEqualTo(200);
  }

  /** Anonymous 401s (no or an expired session) are charged to the IP as well. */
  @Test
  void anonymousUnauthorizedRequestsAreCharged() throws Exception {
    String ip = ip();
    for (int i = 0; i < 4; i++) {
      assertThat(perform(from(get("/api/v1/account"), ip)).getResponse().getStatus()).isEqualTo(401);
    }
    assertThat(code(perform(from(get("/api/v1/account"), ip)))).isEqualTo("RATE_LIMITED");
  }

  /** An encoded heavy path ({@code /le%61derboard}) is still a heavy read. */
  @Test
  void anEncodedHeavyPathIsStillHeavy() throws Exception {
    Fixtures.PlayerRef p = fx.human("encheavy" + SEQ.incrementAndGet());
    String key = fx.apiKey(p, properties.security().apiKeyPepper());
    for (int i = 0; i < 2; i++) {
      MvcResult r = perform(get(java.net.URI.create("/api/v1/le%61derboard")).header("Authorization", "Bearer " + key));
      assertThat(r.getResponse().getStatus()).as(WebSession.contentOf(r)).isNotEqualTo(429);
      assertThat(r.getResponse().getHeader(RateLimitFilter.LIMIT)).isEqualTo("2");
    }
    assertThat(perform(get(java.net.URI.create("/api/v1/le%61derboard")).header("Authorization", "Bearer " + key))
        .getResponse().getStatus()).isEqualTo(429);
  }
}
