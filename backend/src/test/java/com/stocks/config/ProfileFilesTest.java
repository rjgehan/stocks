package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
