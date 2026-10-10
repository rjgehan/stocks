package com.stocks.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.SessionType;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.testkit.TestTimes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.core.MessagePostProcessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/** Shared doubles for the realtime unit tests. */
final class RealtimeTestSupport {

  private RealtimeTestSupport() {}

  /** One message the code under test sent: a topic broadcast ({@code user} null) or a user-queue push. */
  record Sent(String user, String destination, Object payload) {}

  /** Records every send instead of delivering it. */
  static final class CapturingMessaging implements SimpMessageSendingOperations {
    final List<Sent> sent = new CopyOnWriteArrayList<>();

    List<Sent> to(String destination) {
      return sent.stream().filter(s -> s.destination().equals(destination)).toList();
    }

    @Override
    public void convertAndSendToUser(String user, String destination, Object payload) throws MessagingException {
      sent.add(new Sent(user, destination, payload));
    }

    @Override
    public void convertAndSendToUser(String user, String destination, Object payload, Map<String, Object> headers) {
      sent.add(new Sent(user, destination, payload));
    }

    @Override
    public void convertAndSendToUser(String user, String destination, Object payload,
                                     MessagePostProcessor postProcessor) {
      sent.add(new Sent(user, destination, payload));
    }

    @Override
    public void convertAndSendToUser(String user, String destination, Object payload, Map<String, Object> headers,
                                     MessagePostProcessor postProcessor) {
      sent.add(new Sent(user, destination, payload));
    }

    @Override
    public void send(Message<?> message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void send(String destination, Message<?> message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void convertAndSend(Object payload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void convertAndSend(String destination, Object payload) {
      sent.add(new Sent(null, destination, payload));
    }

    @Override
    public void convertAndSend(String destination, Object payload, Map<String, Object> headers) {
      sent.add(new Sent(null, destination, payload));
    }

    @Override
    public void convertAndSend(Object payload, MessagePostProcessor postProcessor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void convertAndSend(Object payload, Map<String, Object> headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void convertAndSend(Object payload, Map<String, Object> headers, MessagePostProcessor postProcessor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void convertAndSend(String destination, Object payload, MessagePostProcessor postProcessor) {
      sent.add(new Sent(null, destination, payload));
    }

    @Override
    public void convertAndSend(String destination, Object payload, Map<String, Object> headers,
                               MessagePostProcessor postProcessor) {
      sent.add(new Sent(null, destination, payload));
    }
  }

  static ObjectProvider<SimpMessageSendingOperations> provider(SimpMessageSendingOperations ops) {
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    factory.registerSingleton("messaging", ops);
    return factory.getBeanProvider(SimpMessageSendingOperations.class);
  }

  /** A calendar that is always in {@code type} (on {@link TestTimes#TRADE_DATE}). */
  static MarketCalendar calendarIn(SessionType type) {
    MarketCalendar calendar = mock(MarketCalendar.class);
    when(calendar.stateAt(any())).thenReturn(new SessionState(type, TestTimes.TRADE_DATE,
        TestTimes.START.minusSeconds(1800), TestTimes.START.plusSeconds(21600), SessionType.POST,
        TestTimes.START.plusSeconds(21600)));
    return calendar;
  }
}
