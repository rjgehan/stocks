package com.stocks.testkit;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Points a Spring context at a fresh database on the JVM's embedded PostgreSQL 18.6 ({@link EmbeddedPg}):
 * {@code CREATE DATABASE stocks_<uuid>}, then {@code spring.datasource.*} as the highest-precedence property source,
 * so Boot's Flyway resolves {@code {vendor}} to {@code postgresql} and applies V1 + {@code V1_0_1__pg_extras}.
 *
 * <p>Under {@code -Ppg-it} it is applied to <b>every</b> Spring test context automatically
 * ({@link EmbeddedPgContextCustomizerFactory}, registered in {@code src/test/resources/META-INF/spring.factories}),
 * except classes annotated {@link H2Only} or named {@code *H2IT}. It can also be used explicitly:
 * {@code @ContextConfiguration(initializers = EmbeddedPgContextInitializer.class)}.
 */
public class EmbeddedPgContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  public static final String PROPERTY_SOURCE = "embeddedPg";

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    String database = EmbeddedPg.createDatabase();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of(
        "spring.datasource.url", EmbeddedPg.jdbcUrl(database),
        "spring.datasource.username", EmbeddedPg.USER,
        "spring.datasource.password", EmbeddedPg.PASSWORD,
        "spring.datasource.driver-class-name", "org.postgresql.Driver",
        "stocks.test.pg.database", database)));
  }
}
