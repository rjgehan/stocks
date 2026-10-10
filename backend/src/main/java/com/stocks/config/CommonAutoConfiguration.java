package com.stocks.config;

import com.stocks.common.time.SystemTimeSource;
import com.stocks.common.time.TimeSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Platform defaults that must back off reliably when a workstream provides its own bean. Registered as an
 * auto-configuration (META-INF/spring/…AutoConfiguration.imports) because auto-configurations are processed after
 * every user bean, so {@code @ConditionalOnMissingBean} is order-independent (the same reasoning as
 * {@code StubsAutoConfiguration}, §27.1).
 *
 * <ul>
 *   <li>{@link TimeSource}: wall clock ({@link SystemTimeSource}). WS2's SIM clock modes (§15.7: {@code wall},
 *       {@code offset}, {@code fixed}) and the test kit replace it with an
 *       {@link com.stocks.common.time.OffsetTimeSource} or {@link com.stocks.common.time.MutableTimeSource}.</li>
 * </ul>
 */
@AutoConfiguration
public class CommonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(TimeSource.class)
  TimeSource timeSource() {
    return new SystemTimeSource();
  }
}
