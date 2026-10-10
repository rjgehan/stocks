package com.stocks.testkit;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * {@code @Import} it to record every application event of the {@code com.stocks.common.events} family a test context
 * publishes; {@link #of} returns those of one type (oldest first). Static, so tests read it without injection.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordedEvents {

  private static final List<Object> EVENTS = new ArrayList<>();

  /** Every recorded event of {@code type}. */
  public static <T> List<T> of(Class<T> type) {
    synchronized (EVENTS) {
      return EVENTS.stream().filter(type::isInstance).map(type::cast).toList();
    }
  }

  @Bean
  Recorder recordedEventsListener() {
    return new Recorder();
  }

  /** The listener bean. */
  public static final class Recorder {
    @EventListener
    public void on(Object event) {
      if (event.getClass().getPackageName().startsWith("com.stocks.common.events")) {
        synchronized (EVENTS) {
          EVENTS.add(event);
        }
      }
    }
  }
}
