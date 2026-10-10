package com.stocks.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.time.TimeSource;
import com.stocks.config.CommonAutoConfiguration;
import com.stocks.config.stubs.ContractStub;
import com.stocks.config.stubs.NoopMarketDataService;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.config.stubs.StubsAutoConfiguration;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.watchlist.WatchlistService;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

/** §27.1 item 4: stub beans make the app boot and back off when a workstream's real bean exists. */
class StubsAutoConfigurationTest {

  static final Instant NOW = Instant.parse("2026-10-09T14:00:00Z");

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class, StubsAutoConfiguration.class));

  @Test
  void registeredInTheAutoConfigurationImports() {
    List<String> imports = new ArrayList<>();
    ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(imports::add);
    assertThat(imports).contains(StubsAutoConfiguration.class.getName(), CommonAutoConfiguration.class.getName());
  }

  @Test
  void everyRemainingStubIsTheOnlyBeanOfItsContractAndThrows501() {
    runner.run(ctx -> {
      assertThat(ctx).hasNotFailed();
      int stubs = 0;
      for (Class<?> contract : ContractServices.ALL) {
        Map<String, ?> beans = ctx.getBeansOfType(contract);
        assertThat(beans).as(contract.getSimpleName()).hasSizeLessThanOrEqualTo(1);
        if (beans.isEmpty()) {
          continue;                                  // the workstream deleted its stub with the real bean
        }
        stubs++;
        Object bean = beans.values().iterator().next();
        assertThat(bean).as(contract.getSimpleName()).isInstanceOf(ContractStub.class);
        assertThat(((ContractStub) bean).stubOwner()).matches("WS[1-4]");
        if (contract == MarketDataService.class) {
          assertThat(bean).isInstanceOf(NoopMarketDataService.class);
          continue;
        }
        for (Method m : contract.getMethods()) {
          if (Modifier.isStatic(m.getModifiers()) || m.isDefault()) {
            continue;
          }
          assertThatThrownBy(() -> invoke(bean, m))
              .as(contract.getSimpleName() + "." + m.getName())
              .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.NOT_IMPLEMENTED);
                assertThat(e.status()).isEqualTo(501);
                assertThat(e.detail()).contains(contract.getSimpleName() + "." + m.getName());
              });
        }
      }
      assertThat(ctx.getBeansOfType(ContractStub.class)).as("every stub is a contract service stub").hasSize(stubs);
    });
  }

  @Test
  void stubsBackOffWhenARealBeanExists() {
    runner.withUserConfiguration(RealBeans.class).run(ctx -> {
      assertThat(ctx).hasNotFailed();
      assertThat(ctx.getBeansOfType(OrderService.class)).hasSize(1);
      assertThat(ctx.getBean(OrderService.class)).isSameAs(ctx.getBean(RealBeans.class).orderService);
      assertThat(ctx.getBean(OrderService.class)).isNotInstanceOf(ContractStub.class);
      assertThat(ctx.getBeansOfType(MarketDataService.class)).hasSize(1);
      assertThat(ctx.getBean(MarketDataService.class)).isNotInstanceOf(NoopMarketDataService.class);
      assertThat(ctx).doesNotHaveBean("orderServiceStub").doesNotHaveBean("noopMarketDataService");
      assertThat(ctx.getBeansOfType(ContractStub.class).values())
          .noneMatch(b -> b instanceof OrderService || b instanceof MarketDataService);
      // the TimeSource default backed off too
      assertThat(ctx.getBean(TimeSource.class)).isInstanceOf(MutableTimeSource.class);
    });
  }

  @Test
  void aComponentImplementingSeveralContractsReplacesEachStub() {
    runner.withBean(BothServices.class, () -> mock(BothServices.class)).run(ctx -> {
      assertThat(ctx.getBeansOfType(OrderService.class)).hasSize(1);
      assertThat(ctx.getBeansOfType(WatchlistService.class)).hasSize(1);
      assertThat(ctx.getBean(OrderService.class)).isSameAs(ctx.getBean(WatchlistService.class));
    });
  }

  @Test
  void everyContractServiceInterfaceIsStubbed() throws IOException, ClassNotFoundException {
    // Interfaces discovered as lists, handles and DTO unions need no stub.
    List<Class<?>> notServices = List.of(com.stocks.contract.jobs.ScheduledJob.class,
        com.stocks.contract.jobs.PeriodicTask.class, com.stocks.contract.marketdata.MarketUpdateListener.class,
        LeaseHandle.class);
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
    List<Class<?>> unstubbed = new ArrayList<>();
    for (Resource r : resolver.getResources("classpath*:com/stocks/contract/**/*.class")) {
      if (r.getURL().toString().contains("/test-classes/") || r.getFilename().startsWith("package-info")) {
        continue;                                   // this test's own helper types
      }
      Class<?> c = Class.forName(readers.getMetadataReader(r).getClassMetadata().getClassName());
      boolean service = c.isInterface() && !c.isSealed() && !c.isAnnotation()
          && !c.getPackageName().endsWith(".dto") && !c.getPackageName().endsWith(".model");
      if (service && !notServices.contains(c) && !ContractServices.ALL.contains(c)) {
        unstubbed.add(c);
      }
    }
    assertThat(unstubbed).as("contract interfaces without a Day-0 stub").isEmpty();
  }

  @Test
  void noopMarketDataKnowsNothing() {
    MarketDataService md = new NoopMarketDataService(new MutableTimeSource(NOW));
    QuoteView q = md.quote(" aapl ");
    assertThat(q.symbol()).isEqualTo("AAPL");
    assertThat(q.quality()).isEqualTo(DataQuality.NONE);
    assertThat(q.bid()).isNull();
    assertThat(q.last()).isNull();
    assertThat(q.asOf()).isNull();
    assertThat(q.halted()).isFalse();
    assertThat(md.quotes(List.of("SPY", "qqq"))).containsOnlyKeys("SPY", "QQQ")
        .allSatisfy((k, v) -> assertThat(v.quality()).isEqualTo(DataQuality.NONE));
    assertThat(md.mark("AAPL").quality()).isEqualTo(DataQuality.NONE);
    assertThat(md.mark("AAPL").price()).isNull();
    assertThat(md.asset("AAPL")).isEmpty();
    assertThat(md.search("app", 10)).isEmpty();
    assertThat(md.executableQuote("AAPL", NOW, com.stocks.common.domain.SessionType.REGULAR)).isEmpty();
    assertThat(md.drainPrints("AAPL")).isEmpty();
    assertThat(md.previousClose("AAPL")).isEmpty();
    assertThat(md.fundamentals("AAPL")).isEmpty();
    assertThat(md.news(java.util.Set.of(), 10, null)).isEmpty();
    assertThat(md.isHalted("AAPL")).isFalse();
    assertThat(md.movers(MoverList.GAINERS, 10).items()).isEmpty();
    assertThat(md.movers(MoverList.GAINERS, 10).asOf()).isEqualTo(NOW);
    var bars = md.bars(new BarRequest("aapl", ChartRange.D5, null, null, null, null, AdjustmentMode.SPLIT, true));
    assertThat(bars.bars()).isEmpty();
    assertThat(bars.timeframe()).isEqualTo(Timeframe.MIN5);
    assertThat(bars.symbol()).isEqualTo("AAPL");
    try (LeaseHandle h = md.lease("AAPL", LeasePriority.VIEWING, "ws:1")) {
      assertThat(h).isNotNull();
    }
    md.releaseOwner("ws:1");
    md.requestRefresh("AAPL", com.stocks.contract.marketdata.RefreshPriority.UI);
    assertThat(md.status().providers()).isEmpty();
  }

  @Test
  void notImplementedStubBehavesLikeAnObject() {
    OrderService a = NotImplementedStub.of(OrderService.class, "WS3");
    assertThat(a).isEqualTo(a).isNotEqualTo(NotImplementedStub.of(OrderService.class, "WS3"));
    assertThat(a.toString()).isEqualTo("NotImplementedStub[OrderService, WS3]");
    assertThat(a.hashCode()).isEqualTo(System.identityHashCode(a));
    assertThat(Map.of(a, 1)).containsKey(a);
  }

  private static Object invoke(Object bean, Method m) throws Throwable {
    Object[] args = new Object[m.getParameterCount()];
    Class<?>[] types = m.getParameterTypes();
    for (int i = 0; i < args.length; i++) {
      args[i] = types[i].isPrimitive() ? Array.get(Array.newInstance(types[i], 1), 0) : null;
    }
    try {
      return m.invoke(bean, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RealBeans {
    final OrderService orderService = mock(OrderService.class);

    @Bean
    OrderService realOrderService() {
      return orderService;
    }

    @Bean
    MarketDataService scriptedMarketData() {
      return mock(MarketDataService.class);
    }

    @Bean
    MutableTimeSource testClock() {
      return new MutableTimeSource(NOW);
    }
  }

  interface BothServices extends OrderService, WatchlistService {}
}
