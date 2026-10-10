package com.stocks.testkit;

import com.stocks.config.StocksProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link StocksProperties} without a Spring context, bound exactly like the app binds them (all {@code @DefaultValue}s
 * apply), plus the overrides given, e.g. {@code TestProperties.stocks("stocks.jobs.enabled", "true")}.
 */
public final class TestProperties {

  public static final String PEPPER = "test-pepper-not-secret-0123456789abcdef";

  private TestProperties() {}

  public static StocksProperties stocks(String... keyValues) {
    Map<String, String> map = new HashMap<>();
    map.put("stocks.security.api-key-pepper", PEPPER);
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return new Binder(new MapConfigurationPropertySource(map)).bindOrCreate("stocks", StocksProperties.class);
  }
}
