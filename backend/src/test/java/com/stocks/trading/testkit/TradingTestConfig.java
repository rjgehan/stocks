package com.stocks.trading.testkit;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.engine.EngineCore;
import com.stocks.trading.engine.EngineRunner;
import com.stocks.trading.engine.testkit.DirectEngineExecutor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The WS3 test world: a fixed clock at {@link TestTimes#START} (Fri 2026-10-09 10:00 ET), the scripted market
 * (§26 {@code ScriptedMarketData}, wired to the engine as its {@code MarketUpdateListener}), the test NYSE calendar
 * and a {@link DirectEngineExecutor}. Use with {@code stocks.engine.auto-start=false}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TradingTestConfig {

  @Bean
  MutableTimeSource mutableTimeSource() {
    return TestTimes.clock();
  }

  @Bean
  ScriptedMarketData scriptedMarketData(MutableTimeSource time) {
    return new ScriptedMarketData(time);
  }

  @Bean
  TestCalendar testCalendar() {
    return new TestCalendar();
  }

  @Bean
  DirectEngineExecutor directEngineExecutor(EngineCore core, MutableTimeSource time) {
    return new DirectEngineExecutor(core, time);
  }

  @Bean
  SmartInitializingSingleton scriptedMarketListens(ScriptedMarketData md, EngineRunner engine) {
    return () -> md.addListener(engine);
  }
}
