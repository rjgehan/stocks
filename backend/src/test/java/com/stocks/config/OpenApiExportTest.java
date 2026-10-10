package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The {@code make openapi} entry point (§26 CI step 4, §24.6). Boots the {@code test} profile (the real controllers
 * only), GETs {@code /v3/api-docs} as an authenticated user and writes the body to the file named by
 * {@code -Dstocks.openapi.output}; skipped when that property is absent, so it is a no-op in {@code ./mvnw verify}.
 *
 * <pre>
 * cd backend && ./mvnw -B -q test -Dtest=OpenApiExportTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dstocks.openapi.output=$PWD/target/openapi/openapi.json
 * </pre>
 * The Makefile then pretty-prints it into {@code docs/openapi.json} and runs {@code npm run gen:api}.
 * {@code OpenApiRulesIT} checks the §18 rules on every build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "stocks.openapi.output", matches = ".+")
class OpenApiExportTest {

  @Autowired MockMvc mvc;

  @Test
  void writesTheApiDocs() throws Exception {
    MvcResult r = mvc.perform(get("/v3/api-docs").with(user("openapi-export"))).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).startsWith("{").contains("\"openapi\":\"3.1");
    Path out = Path.of(System.getProperty("stocks.openapi.output")).toAbsolutePath();
    Files.createDirectories(out.getParent());
    Files.writeString(out, body, StandardCharsets.UTF_8);
  }
}
