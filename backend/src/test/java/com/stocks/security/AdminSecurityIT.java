package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * §17.4 layer 5: every route under {@code /api/v1/admin/**} (discovered from the handler mappings, so admin
 * controllers added later are covered automatically) refuses anonymous callers, a bot's key, the admin's own key and
 * a non-admin session with 401 or 403; only an admin <b>session</b> gets through. Any {@code Authorization} header is
 * 403 {@code ADMIN_REQUIRES_SESSION} (D14), even next to a valid admin session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityProbes.class)
class AdminSecurityIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

  Fixtures fx;
  Fixtures.PlayerRef admin;
  Fixtures.PlayerRef human;
  String botKey;
  String adminKey;

  record Route(HttpMethod method, String path) {}

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    String pepper = properties.security().apiKeyPepper();
    admin = fx.admin("root" + n);
    human = fx.human("pleb" + n);
    botKey = fx.apiKey(fx.ai("robot" + n, admin), pepper);
    adminKey = fx.apiKey(admin, pepper);
  }

  List<Route> adminRoutes() {
    List<Route> routes = new ArrayList<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      Set<String> patterns = info.getPatternValues();
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : patterns) {
        if (!pattern.startsWith("/api/v1/admin/")) {
          continue;
        }
        String path = pattern.replaceAll("\\{[^}]+}", "x");
        if (methods.isEmpty()) {
          routes.add(new Route(HttpMethod.GET, path));
        }
        for (RequestMethod m : methods) {
          routes.add(new Route(HttpMethod.valueOf(m.name()), path));
        }
      }
    }
    return routes;
  }

  static MockHttpServletRequestBuilder req(Route r) {
    return request(r.method(), r.path()).contentType(MediaType.APPLICATION_JSON).content("{}");
  }

  static String code(MvcResult r) {
    return JsonPath.read(WebSession.contentOf(r), "$.code");
  }

  @Test
  void everyAdminRouteRefusesEveryoneButAnAdminSession() throws Exception {
    List<Route> routes = adminRoutes();
    assertThat(routes).as("admin routes discovered").contains(new Route(HttpMethod.GET, "/api/v1/admin/__probe"));
    WebSession pleb = WebSession.login(mvc, human.handle(), Fixtures.PASSWORD);
    WebSession root = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    for (Route r : routes) {
      MvcResult anonymous = mvc.perform(req(r)).andReturn();
      assertThat(anonymous.getResponse().getStatus()).as("anonymous %s", r).isIn(401, 403);

      MvcResult bot = mvc.perform(req(r).header("Authorization", "Bearer " + botKey)).andReturn();
      assertThat(bot.getResponse().getStatus()).as("bot key %s", r).isEqualTo(403);
      assertThat(code(bot)).isEqualTo("ADMIN_REQUIRES_SESSION");

      MvcResult ownKey = mvc.perform(req(r).header("Authorization", "Bearer " + adminKey)).andReturn();
      assertThat(ownKey.getResponse().getStatus()).as("admin's own key %s", r).isEqualTo(403);
      assertThat(code(ownKey)).isEqualTo("ADMIN_REQUIRES_SESSION");

      MvcResult nonAdmin = pleb.perform(req(r));
      assertThat(nonAdmin.getResponse().getStatus()).as("non-admin session %s", r).isEqualTo(403);
      assertThat(code(nonAdmin)).isEqualTo("FORBIDDEN");

      MvcResult sessionPlusHeader = root.perform(req(r).header("Authorization", "Basic YWRtaW46eA=="));
      assertThat(code(sessionPlusHeader)).as("session + Authorization %s", r).isEqualTo("ADMIN_REQUIRES_SESSION");

      MvcResult ok = root.perform(req(r));
      assertThat(ok.getResponse().getStatus()).as("admin session %s", r).isNotIn(401, 403);
    }
  }

  @Test
  void adminWritesNeedCsrf() {
    WebSession root = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    MvcResult without = root.performWithoutCsrf(request(HttpMethod.POST, "/api/v1/admin/__probe"));
    assertThat(without.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(without)).isEqualTo("CSRF_INVALID");
    MvcResult with = root.perform(request(HttpMethod.POST, "/api/v1/admin/__probe"));
    assertThat(with.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(WebSession.contentOf(with), "$.handle")).isEqualTo(admin.handle());
  }

  @Test
  void anAdminKeyNeverCarriesTheAdminRole() throws Exception {
    MvcResult me = mvc.perform(request(HttpMethod.GET, "/api/v1/me").header("Authorization", "Bearer " + adminKey))
        .andReturn();
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.role")).isEqualTo("ADMIN"); // displayed role
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.authMethod")).isEqualTo("API_KEY");
  }
}
