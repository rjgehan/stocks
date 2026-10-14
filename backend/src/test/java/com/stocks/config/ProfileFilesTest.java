package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.identity.SettingKey;
import com.stocks.testkit.TestProperties;
import com.stocks.trading.config.TradingSettings;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The profile files parse, and their placeholders resolve as §23/§24.3 intend (without booting a context). */
class ProfileFilesTest {

  private static StandardEnvironment load(String... files) throws Exception {
    StandardEnvironment env = new StandardEnvironment();
    // only the YAML files (plus random.*): the build machine's environment variables must not leak in
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    env.getPropertySources().addLast(new RandomValuePropertySource());
    // later files win, as profile files override application.yml
    for (String file : files) {
      List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
      sources.forEach(s -> env.getPropertySources().addFirst(s));
    }
    return env;
  }

  @Test
  void prodTrustsOnlyLocalProxiesByDefault() throws Exception {
    StandardEnvironment env = load("application.yml", "application-prod.yml");
    String regex = env.getProperty("server.tomcat.remoteip.internal-proxies");
    Pattern p = Pattern.compile(regex);
    assertThat(p.matcher("127.0.0.1").matches()).isTrue();
    assertThat(p.matcher("172.18.0.1").matches()).isTrue();   // Docker bridge gateway
    assertThat(p.matcher("192.168.1.20").matches()).isTrue();
    assertThat(p.matcher("::1").matches()).isTrue();
    assertThat(p.matcher("8.8.8.8").matches()).isFalse();
    assertThat(p.matcher("172.32.0.1").matches()).isFalse();
    assertThat(env.getProperty("server.servlet.session.cookie.name")).isEqualTo("__Host-stocks_sid");
    assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
    assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    // the actuator is reachable from inside the app container only (not from stocks-edge/stocks-backend peers)
    assertThat(env.getProperty("management.server.port")).isEqualTo("8081");
    assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
    assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("always");
  }

  @Test
  void prodHasNoUsableDefaultsForSecrets() throws Exception {
    StandardEnvironment env = load("application.yml", "application-prod.yml");
    for (String key : List.of("spring.datasource.url", "spring.datasource.password", "stocks.public-url",
        "stocks.security.api-key-pepper", "stocks.engine.seed")) {
      assertThatThrownBy(() -> env.getRequiredProperty(key)).as(key).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void devAndTestProfiles() throws Exception {
    StandardEnvironment dev = load("application.yml", "application-dev.yml");
    assertThat(dev.getProperty("spring.datasource.url")).startsWith("jdbc:h2:file:./.data/stocks-dev;MODE=PostgreSQL;")
        .contains("DATABASE_TO_LOWER=TRUE").contains("DEFAULT_NULL_ORDERING=HIGH");
    assertThat(dev.getProperty("spring.flyway.out-of-order")).isEqualTo("true");
    assertThat(dev.getProperty("stocks.bootstrap.seed-demo")).isEqualTo("true");
    assertThat(dev.getProperty("spring.h2.console.enabled")).isEqualTo("true");

    StandardEnvironment test = load("application.yml", "application-test.yml");
    assertThat(test.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:stocks-")
        .contains("MODE=PostgreSQL").contains("DB_CLOSE_DELAY=-1");
    assertThat(test.getProperty("stocks.jobs.enabled")).isEqualTo("false");
    assertThat(test.getProperty("stocks.single-instance.enabled")).isEqualTo("false");
    assertThat(test.getProperty("stocks.marketdata.sim.clock")).isEqualTo("fixed");
    assertThat(test.getProperty("stocks.marketdata.sim.seed")).isEqualTo("42"); // merged, not replaced
  }

  @Test
  void theShippedStartingCashIs500k() throws Exception {
    // the code default, application.yml without STARTING_CASH_DEFAULT, and the league setting's own default
    assertThat(TestProperties.stocks().bootstrap().defaultStartingCash()).isEqualByComparingTo("500000.00");
    assertThat(load("application.yml").getProperty("stocks.bootstrap.default-starting-cash")).isEqualTo("500000.00");
    assertThat(load("application.yml", "application-prod.yml").getProperty("stocks.bootstrap.default-starting-cash"))
        .isEqualTo("500000.00");
    assertThat(SettingKey.DEFAULT_STARTING_CASH.defaultValue()).isEqualTo("500000.00");
    assertThat(TradingSettings.DEFAULT_STARTING_CASH).isEqualByComparingTo("500000.00");
    // the suites (and make e2e) are calibrated to a $500 account: the test profile pins that
    assertThat(new BigDecimal(load("application.yml", "application-test.yml")
        .getProperty("stocks.bootstrap.default-starting-cash"))).isEqualByComparingTo("500.00");   // YAML: 500.0
  }

  @Test
  void rateLimitAndCacheDefaultsAreTheSpecValues() throws Exception {
    StocksProperties defaults = TestProperties.stocks();   // @DefaultValue
    StandardEnvironment yml = load("application.yml");      // application.yml
    assertThat(defaults.ratelimit().writesPerMin()).isEqualTo(20);
    assertThat(defaults.ratelimit().writesPerDay()).isEqualTo(300);
    assertThat(defaults.ratelimit().keyReadsPerMin()).isEqualTo(120);
    assertThat(defaults.ratelimit().keyReadsBurst()).isEqualTo(30);
    assertThat(defaults.ratelimit().loginPerMinIp()).isEqualTo(10);
    assertThat(defaults.ratelimit().signupPerHourIp()).isEqualTo(5);
    assertThat(defaults.security().apiKeyCacheTtl()).hasSeconds(60);
    assertThat(yml.getProperty("stocks.ratelimit.writes-per-min")).isEqualTo("20");
    assertThat(yml.getProperty("stocks.ratelimit.anon-per-min-ip")).isEqualTo("300");
    assertThat(yml.getProperty("stocks.security.api-key-cache-ttl")).isEqualTo("60s");
    StandardEnvironment test = load("application.yml", "application-test.yml");
    assertThat(test.getProperty("stocks.security.api-key-cache-ttl")).isEqualTo("0s");
  }
}
