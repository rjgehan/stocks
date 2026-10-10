package com.stocks.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * The {@code eventsExecutor} of §5.3: every listener of the {@code com.stocks.common.events} family hands off to it with
 * {@code @Async("eventsExecutor")}, so the engine thread that publishes after commit never waits on a listener. One
 * virtual thread per event; delivery is best-effort (listeners that write rows are idempotent by {@code source_ref}).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

  public static final String EVENTS_EXECUTOR = "eventsExecutor";

  /**
   * Not a default candidate: injection by type ({@code Executor}) and Boot's task-executor conditions ignore it, so
   * Boot's {@code applicationTaskExecutor} stays; {@code @Async("eventsExecutor")} finds it by name.
   */
  @Bean(name = EVENTS_EXECUTOR, destroyMethod = "close", defaultCandidate = false)
  ExecutorService eventsExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("events-", 0).factory());
  }
}
