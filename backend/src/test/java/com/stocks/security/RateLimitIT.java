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
}
