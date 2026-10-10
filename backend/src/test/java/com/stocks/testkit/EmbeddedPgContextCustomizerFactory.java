package com.stocks.testkit;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Applies {@link EmbeddedPgContextInitializer} to every Spring test context when {@code stocks.test.pg=true}
 * ({@code -Ppg-it}), so the whole suite runs on PostgreSQL 18.6 with a fresh database per context. Skipped for
 * {@link H2Only} classes and classes named {@code *H2IT}. Registered in {@code META-INF/spring.factories}.
 *
 * <p>Highest precedence: it runs before Spring's {@code @DynamicPropertySource} customizer, so a test that sets its own
 * {@code spring.datasource.*} dynamically still wins.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmbeddedPgContextCustomizerFactory implements ContextCustomizerFactory {

  /** One shared instance: equal for every class, so it never splits Spring's context cache. */
  static final ContextCustomizer CUSTOMIZER = new EmbeddedPgCustomizer();

  @Override
  public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                   List<ContextConfigurationAttributes> configAttributes) {
    if (!EmbeddedPg.enabled() || keepsH2(testClass)) {
      return null;
    }
    return CUSTOMIZER;
  }

  static boolean keepsH2(Class<?> testClass) {
    for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
      if (c.isAnnotationPresent(H2Only.class) || c.getSimpleName().endsWith("H2IT")) {
        return true;
      }
    }
    return false;
  }

  private static final class EmbeddedPgCustomizer implements ContextCustomizer {
    private final EmbeddedPgContextInitializer initializer = new EmbeddedPgContextInitializer();

    @Override
    public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
      initializer.initialize(context);
    }
  }
}
