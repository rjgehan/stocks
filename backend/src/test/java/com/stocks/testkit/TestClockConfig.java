package com.stocks.testkit;

import com.stocks.common.time.MutableTimeSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * {@code @Import(TestClockConfig.class)} gives a Spring test context a {@link MutableTimeSource} at
 * {@link TestTimes#START} (the {@code test} profile's fixed clock, §23.2). The platform's default
 * {@code SystemTimeSource} backs off ({@code CommonAutoConfiguration}). Inject {@code MutableTimeSource} to move time,
 * or call {@code POST /api/v1/test/clock}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

  @Bean
  MutableTimeSource mutableTimeSource() {
    return TestTimes.clock();
  }
}
