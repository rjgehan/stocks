package com.stocks.marketdata.config;

import com.stocks.common.time.TimeSource;
import com.stocks.config.CommonAutoConfiguration;
import com.stocks.marketdata.sim.SimClockModes;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The application {@link TimeSource} chosen by the SIM clock modes (§15.7, §23.2): {@code wall} in production,
 * {@code fixed} (a {@code MutableTimeSource}) in the {@code test} profile, {@code offset} anchored at
 * 2026-10-09T14:00Z in e2e. An auto-configuration ordered <b>before</b> {@link CommonAutoConfiguration}, so the
 * platform's wall-clock default backs off, while a test's own {@code TimeSource} bean (the test kit's
 * {@code TestClockConfig}) still wins: auto-configurations are processed after every user bean.
 */
@AutoConfiguration(before = CommonAutoConfiguration.class)
public class MarketDataClockAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(TimeSource.class)
  TimeSource simClockTimeSource(Environment environment) {
    MarketDataProperties props = Binder.get(environment)
        .bind("stocks.marketdata", MarketDataProperties.class)
        .orElseGet(MarketDataProperties::defaults);
    return SimClockModes.timeSource(props);
  }
}
