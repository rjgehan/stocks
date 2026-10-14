package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.stocks.testkit.ContractMocksWebTest;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Column R of §21.1 (and {@code POST /me/api-keys}, §18.1): exactly these routes carry {@link RecentAuth}, and each
 * one answers 403 {@code RECENT_AUTH_REQUIRED} to an admin whose password check is older than 15 minutes.
 */
class RecentAuthRoutesIT extends ContractMocksWebTest {

  static final Set<String> SPEC_R = Set.of(
      "POST /api/v1/admin/players",
      "PATCH /api/v1/admin/players/{handle}",
      "POST /api/v1/admin/players/{handle}/freeze",
      "POST /api/v1/admin/players/{handle}/unfreeze",
      "POST /api/v1/admin/players/{handle}/password",
      "POST /api/v1/admin/players/{handle}/reset",
      "POST /api/v1/admin/players/{handle}/api-keys",
      "POST /api/v1/admin/cash-transfers",
      "PUT /api/v1/admin/settings",
      "PATCH /api/v1/admin/settings",
      "POST /api/v1/admin/system/jobs/{jobName}/run",
      "POST /api/v1/admin/engine/halt",
      "POST /api/v1/admin/engine/resume",
      "DELETE /api/v1/admin/orders/{orderId}",
      "POST /api/v1/admin/calendar/overrides",
      "DELETE /api/v1/admin/calendar/overrides/{date}",
      "POST /api/v1/admin/corporate-actions/{id}/resolve",
      "POST /api/v1/me/api-keys");

  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

  Set<String> annotated() {
    Set<String> out = new TreeSet<>();
    mappings.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
      if (RecentAuthInterceptor.requiresRecentAuth(method)) {
        info.getPatternValues().forEach(p -> info.getMethodsCondition().getMethods()
            .forEach(m -> out.add(m.name() + " " + p)));
      }
    });
    return out;
  }

  @Test
  void exactlyTheSpecRoutesNeedARecentPassword() {
    assertThat(annotated()).containsExactlyInAnyOrderElementsOf(SPEC_R);
  }

  @Test
  void aStaleAdminSessionGetsRecentAuthRequiredOnEveryRRoute() {
    Fixtures.PlayerRef admin = fx.admin(handle("stale"));
    WebSession root = adminSession(admin);
    time.advance(Duration.ofMinutes(16));
    for (String route : SPEC_R) {
      String[] parts = route.split(" ");
      String path = parts[1].replaceAll("\\{[^}]+}", "x");
      MvcResult r = root.perform(request(HttpMethod.valueOf(parts[0]), path).contentType(MediaType.APPLICATION_JSON)
          .content("{}"));
      // the interceptor runs before the body is read or validated
      assertThat(r.getResponse().getStatus()).as(route + " " + body(r)).isEqualTo(403);
      assertThat(code(r)).as(route).isEqualTo("RECENT_AUTH_REQUIRED");
    }
  }
}
