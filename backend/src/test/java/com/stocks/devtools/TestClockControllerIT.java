package com.stocks.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.time.SystemTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.jobs.JobRunGuard;
import com.stocks.jobs.JobScheduler;
import com.stocks.security.SecurityProbes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.WebSession;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** §18.5 {@code POST /api/v1/test/clock}: any authenticated player moves the test clock forward. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityProbes.class) // same context as the security ITs (it brings the MutableTimeSource)
class TestClockControllerIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;

  static MockHttpServletRequestBuilder advance(String duration) {
    return post("/api/v1/test/clock").contentType(MediaType.APPLICATION_JSON)
        .content("{\"advance\":\"" + duration + "\"}");
  }

  @Test
  void aKeyOrASessionMovesTheClock() throws Exception {
    Fixtures fx = new Fixtures(jdbc, time);
    Fixtures.PlayerRef p = fx.human("clockuser" + System.identityHashCode(this));
    String key = fx.apiKey(p, properties.security().apiKeyPepper());
    Instant before = time.now();

    MvcResult r = mvc.perform(advance("PT16M").header("Authorization", "Bearer " + key)).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    Instant now = Instant.parse(JsonPath.read(r.getResponse().getContentAsString(), "$.now"));
    assertThat(now).isEqualTo(before.plus(Duration.ofMinutes(16)));
    assertThat(time.now()).isEqualTo(now);

    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    assertThat(s.perform(advance("PT1S")).getResponse().getStatus()).isEqualTo(200);
    assertThat(s.performWithoutCsrf(advance("PT1S")).getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void badDurationsAndAnonymousCallersAreRefused() throws Exception {
    Fixtures fx = new Fixtures(jdbc, time);
    String key = fx.apiKey(fx.human("clockbad" + System.identityHashCode(this)), properties.security().apiKeyPepper());
    for (String bad : new String[] {"PT0S", "-PT1M", "P8D", "16 minutes"}) {
      MvcResult r = mvc.perform(advance(bad).header("Authorization", "Bearer " + key)).andReturn();
      assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
      assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }
    assertThat(mvc.perform(advance("PT1M")).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void aWallClockCannotBeMoved() {
    TestDb db = TestDb.h2();
    StocksProperties props = com.stocks.testkit.TestProperties.stocks();
    SystemTimeSource wall = new SystemTimeSource();
    JobScheduler scheduler = new JobScheduler(emptyProvider(), emptyProvider(),
        NotImplementedStub.of(MarketCalendar.class, "WS2"), new JobRunGuard(db.jdbc(), wall), wall, props);
    TestClockController controller = new TestClockController(wall, scheduler);
    assertThatThrownBy(() -> controller.advance(new TestClockController.AdvanceRequest("PT1M")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_IMPLEMENTED));
  }

  @SuppressWarnings("unchecked")
  static <T> ObjectProvider<T> emptyProvider() {
    return new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(
        (Class<T>) Object.class);
  }
}
