package com.stocks.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §17.9.5 with {@code stocks.oauth.enabled=false}: the well-known and {@code /oauth2} paths answer 404, JWTs are not
 * accepted (they are just unknown keys) and the 401 challenge has no {@code resource_metadata}. A token minted while
 * OAuth was on stops working at once.
 */
@SpringBootTest(properties = "stocks.oauth.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class OAuthDisabledIT {

  @Autowired MockMvc mvc;
  @Autowired AccessTokens tokens;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;

  @Test
  void everythingOAuthIsOff() throws Exception {
    for (String path : new String[] {"/.well-known/oauth-protected-resource/mcp",
        "/.well-known/oauth-authorization-server", "/oauth2/jwks"}) {
      assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(404);
    }
    assertThat(mvc.perform(get("/oauth2/authorize").queryParam("client_id", "x")).andReturn().getResponse()
        .getStatus()).isEqualTo(404);
    assertThat(mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andReturn().getResponse().getStatus()).isEqualTo(404);

    MvcResult anonymous = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    assertThat(anonymous.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"stocks\"");

    Fixtures fx = new Fixtures(jdbc, time);
    Fixtures.PlayerRef p = fx.human("odisabled");
    String jwt = tokens.mint(p.publicId(), p.publicId(), "https://claude.ai/oauth/mcp-oauth-client-metadata",
        Set.of("market:read"), "ogr_none").value();
    MvcResult r = OAuthTestClient.mcpCall(mvc, jwt, "whoami", "{}");
    assertThat(r.getResponse().getStatus()).isEqualTo(401);
    assertThat(WebSession.contentOf(r)).contains("INVALID_API_KEY");
  }
}
