package com.stocks.alerts;

import static org.mockito.Mockito.mock;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.realtime.NotificationCreated;
import com.stocks.realtime.NotificationService;
import com.stocks.realtime.RecordingMarketData;
import com.stocks.realtime.SubscriptionTracker;
import com.stocks.realtime.UserPush;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/**
 * Price alerts wired without Spring on a fresh database (H2, or PostgreSQL under {@code -Ppg-it}): the real repository,
 * service, monitor and {@code NotificationService}, {@link ScriptedMarketData} for quotes and the bundled NYSE
 * calendar. The clock starts at {@link TestTimes#START} (Fri 2026-10-09 10:00 ET, regular session).
 */
final class AlertKit implements AutoCloseable {

  final MutableTimeSource time = TestTimes.clock();
  final TestDb db = TestDb.fresh();
  final Fixtures fx = new Fixtures(db.jdbc(), time);
  final ScriptedMarketData market = new ScriptedMarketData(time).withAsset("AAPL").withAsset("NVDA");
  final MarketCalendar calendar = InMemoryCalendars.bundled();
  final List<NotificationCreated> created = new CopyOnWriteArrayList<>();
  final NotificationService notifications;
  final AlertRepository repo;
  final AlertMonitor monitor;
  final AlertService service;
  final AlertCorporateActions corporateActions;

  AlertKit() {
    this(AlertProperties.defaults());
  }

  AlertKit(AlertProperties props) {
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    factory.registerSingleton("messaging", mock(SimpMessageSendingOperations.class));
    UserPush push = new UserPush(new SubscriptionTracker(new RecordingMarketData(time), time),
        factory.getBeanProvider(SimpMessageSendingOperations.class));
    notifications = new NotificationService(db.jdbc(), new PublicId(time), time, JacksonConfig.newMapper(), push,
        e -> {
          if (e instanceof NotificationCreated n) {
            created.add(n);
          }
        });
    repo = new AlertRepository(db.jdbc());
    monitor = new AlertMonitor(repo, notifications, market, calendar, time, props);
    market.addListener(monitor);
    service = new AlertService(repo, monitor, market, db.jdbc(), new PublicId(time), time, props);
    corporateActions = new AlertCorporateActions(repo, monitor, notifications, db.jdbc(), time);
  }

  /** PRICE_ALERT notifications of the player, newest first. */
  List<com.stocks.contract.realtime.dto.NotificationDto> alertsOf(long playerId) {
    return notifications.list(playerId, false, null, 100).items().stream()
        .filter(n -> n.kind() == com.stocks.common.domain.NotificationKind.PRICE_ALERT).toList();
  }

  @Override
  public void close() {
    if (db.dataSource() instanceof com.zaxxer.hikari.HikariDataSource pool) {
      pool.close();
    }
  }
}
