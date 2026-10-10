package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** §23.2 prod fail-fast: every listed misconfiguration is reported; a correct prod environment passes. */
class ProdSafetyCheckTest {

  static MockEnvironment good() {
    MockEnvironment env = new MockEnvironment()
        .withProperty("stocks.public-url", "https://stocks.example.com")
        .withProperty("stocks.security.api-key-pepper", "x0ZJxYbZcFQXn8hqU3o4XW2bF2c6m1bq6j3Vt5bJk0dJYzVhR1FkXo2sYQ6fWm7T")
        .withProperty("stocks.engine.seed", "a-secret-seed")
        .withProperty("stocks.marketdata.profile", "hybrid")
        .withProperty("stocks.marketdata.alpaca.key-id", "PK123")
        .withProperty("stocks.marketdata.alpaca.secret-key", "secret");
    env.setActiveProfiles("prod");
    return env;
  }

  @Test
  void aCorrectProductionEnvironmentPasses() {
    assertThat(ProdSafetyCheck.problems(good(), false)).isEmpty();
  }

  @Test
  void everyMisconfigurationIsReported() {
    MockEnvironment env = new MockEnvironment()
        .withProperty("stocks.public-url", "http://stocks.example.com")
        .withProperty("stocks.security.api-key-pepper", "short")
        .withProperty("stocks.engine.seed", "dev-seed-change-me")
        .withProperty("stocks.marketdata.profile", "simulated");
    env.setActiveProfiles("prod", "test");
    List<String> problems = ProdSafetyCheck.problems(env, true);
    assertThat(problems).hasSize(6)
        .anySatisfy(p -> assertThat(p).contains("APP_PUBLIC_URL"))
        .anySatisfy(p -> assertThat(p).contains("API_KEY_PEPPER"))
        .anySatisfy(p -> assertThat(p).contains("ENGINE_SEED"))
        .anySatisfy(p -> assertThat(p).contains("MARKETDATA_PROFILE"))
        .anySatisfy(p -> assertThat(p).contains("'test'"))
        .anySatisfy(p -> assertThat(p).contains("TestClockController"));
  }

  @Test
  void hybridNeedsAlpacaKeysAndSimulatedNeedsTheOverride() {
    MockEnvironment env = good().withProperty("stocks.marketdata.alpaca.key-id", "");
    assertThat(ProdSafetyCheck.problems(env, false)).singleElement().asString().contains("ALPACA_KEY_ID");
    MockEnvironment sim = good().withProperty("stocks.marketdata.profile", "simulated")
        .withProperty("stocks.marketdata.allow-simulated-in-prod", "true");
    assertThat(ProdSafetyCheck.problems(sim, false)).isEmpty();
  }

  @Test
  void unresolvablePlaceholdersCountAsMissing() {
    MockEnvironment env = good().withProperty("stocks.engine.seed", "${ENGINE_SEED_NOT_SET}");
    assertThat(ProdSafetyCheck.problems(env, false)).singleElement().asString().contains("ENGINE_SEED");
  }

  @Test
  void pepperLengthCountsDecodedBase64Bytes() {
    assertThat(ProdSafetyCheck.pepperBytes("AAAA")).isEqualTo(3);
    assertThat(ProdSafetyCheck.pepperBytes("not base64 but a long passphrase!")).isEqualTo(33);
    assertThat(ProdSafetyCheck.pepperBytes("")).isZero();
  }
}
