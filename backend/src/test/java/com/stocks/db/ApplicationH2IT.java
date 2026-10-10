package com.stocks.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.id.Ulid;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.RequestIds;
import com.stocks.config.StocksProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the whole application on the {@code test} profile (in-memory H2, MODE=PostgreSQL): Boot's Flyway runs
 * {@code common/V1__init.sql} + {@code h2/V1_0_1__h2_extras.sql}, then the {@link SchemaContract} runs against it.
 * Also checks the Day-0 platform wiring: Jackson rules on Boot's mapper, {@code stocks.*} binding, the TimeSource
 * default backing off, request ids and problem details through the real filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationH2IT extends SchemaContract {

  static final Instant START = Instant.parse("2026-10-09T14:00:00Z");

  @TestConfiguration
  static class TestClock {
    @Bean
    MutableTimeSource mutableTimeSource() {
      return new MutableTimeSource(START);
    }
  }

  @Autowired JdbcClient jdbcClient;
  @Autowired DataSource dataSource;
  @Autowired ApplicationContext context;
  @Autowired Environment environment;
  @Autowired JsonMapper jsonMapper;
  @Autowired StocksProperties properties;
  @Autowired TimeSource timeSource;
  @Autowired PublicId publicId;
  @Autowired MockMvc mvc;

  @Override
  protected JdbcClient jdbc() {
    return jdbcClient;
  }

  @Override
  protected String vendor() {
    return "h2";
  }

  @Test
  void runsOnAnInMemoryH2InPostgresMode() throws Exception {
    try (var c = dataSource.getConnection()) {
      assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("H2");
      assertThat(c.getMetaData().getURL()).startsWith("jdbc:h2:mem:stocks-");
    }
    assertThat(jdbcClient.sql("SELECT setting_value FROM information_schema.settings WHERE setting_name = 'MODE'")
        .query(String.class).single()).isEqualToIgnoringCase("PostgreSQL");
    assertThat(jdbcClient.sql("SELECT setting_value FROM information_schema.settings WHERE setting_name = 'DEFAULT_NULL_ORDERING'")
        .query(String.class).single()).isEqualToIgnoringCase("HIGH");
    assertThat(environment.getActiveProfiles()).contains("test");
    if (Boolean.getBoolean("stocks.test.pg")) {
      assertThat(environment.getActiveProfiles()).as("-Ppg-it adds the pgit profile (§23.2)").contains("pgit");
    }
  }

  @Test
  void bootJsonMapperFollowsSection6_3() throws Exception {
    record Dto(BigDecimal price, BigDecimal limitPrice, Instant at) {}
    String json = jsonMapper.writeValueAsString(new Dto(new BigDecimal("227.4000"), null, START));
    assertThat(json).isEqualTo("{\"price\":\"227.4000\",\"limitPrice\":null,\"at\":\"2026-10-09T14:00:00.000Z\"}");
  }

  @Test
  void stocksPropertiesBindTheWs1Keys() {
    assertThat(properties.publicUrl()).isEqualTo("http://localhost:5173");
    assertThat(properties.jobs().enabled()).isFalse();             // test profile
    assertThat(properties.singleInstance().enabled()).isFalse();   // test profile
    assertThat(properties.bootstrap().defaultStartingCash()).isEqualByComparingTo("500.00");
    assertThat(properties.bootstrap().seedDemo()).isFalse();
    assertThat(properties.security().loginMaxFailures()).isEqualTo(10);
    assertThat(properties.security().loginLockout()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.security().recentAuth()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.security().extraWsOrigins()).isEmpty();
    // the test profile lifts every bucket (fixed clock, one IP); the defaults are checked in ProfileFilesTest
    assertThat(properties.ratelimit().enabled()).isTrue();
    assertThat(properties.ratelimit().writesPerMin()).isEqualTo(100000);
    assertThat(properties.ratelimit().anonPerMinIp()).isEqualTo(100000);
    assertThat(properties.security().apiKeyCacheTtl()).isZero();
    assertThat(properties.security().playerStatusCacheTtl()).isZero();
    assertThat(environment.getProperty("stocks.version")).isNotBlank().doesNotContain("@");
    assertThat(environment.getProperty("spring.ai.mcp.server.version")).isEqualTo(environment.getProperty("stocks.version"));
  }

  @Test
  void aWorkstreamTimeSourceReplacesTheDefault() {
    assertThat(context.getBeansOfType(TimeSource.class)).hasSize(1);
    assertThat(timeSource).isInstanceOf(MutableTimeSource.class);
    String id = publicId.next(Prefix.ORDER);
    assertThat(Ulid.timestamp(id.substring(4))).isEqualTo(timeSource.now());
  }

  @Test
  void requestIdsAndProblemDetailsGoThroughTheRealStack() throws Exception {
    // anonymous: Spring Security answers (Boot's default chain until WS1's SecurityConfig lands), with a request id
    mvc.perform(get("/api/v1/nothing-here"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(RequestIds.HEADER, org.hamcrest.Matchers.startsWith("req_")));
    // authenticated: an unknown path is a 404 NOT_FOUND problem
    mvc.perform(get("/api/v1/nothing-here").with(user("alice")).header(RequestIds.HEADER, "it-1"))
        .andExpect(status().isNotFound())
        .andExpect(header().string(RequestIds.HEADER, "it-1"))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.type").value("/errors/NOT_FOUND"))
        .andExpect(jsonPath("$.requestId").value("it-1"));
  }

  @Test
  void flywayLocationsResolveTheVendorPlaceholder() {
    String locations = environment.getProperty("spring.flyway.locations");
    assertThat(locations).isEqualTo("classpath:db/migration/common,classpath:db/migration/{vendor}");
    Map<String, Object> extras = jdbcClient.sql("SELECT script FROM flyway_schema_history WHERE version = '1.0.1'")
        .query().singleRow();
    assertThat(extras.get("script")).isEqualTo("V1_0_1__h2_extras.sql");
    assertThat(Arrays.asList(environment.getActiveProfiles())).doesNotContain("dev", "prod");
  }
}
