package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.NotificationKind;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TransferType;
import com.stocks.common.domain.ViolationType;
import com.stocks.common.error.ApiException;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.CashTransferPosted;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.events.ViolationIssued;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Page;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.contract.realtime.dto.OrderUpdateDto;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.realtime.RealtimeTestSupport.CapturingMessaging;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19 user events: domain events become {@code /user/queue/orders} and {@code /user/queue/account} pushes for
 * connected players, and notifications (written whether or not the player is connected, once per source_ref).
 */
class UserEventPublisherTest {

  MutableTimeSource time;
  TestDb db;
  Fixtures.PlayerRef alice;
  OrderQueries orders;
  AccountQueries accounts;
  CapturingMessaging messaging;
  SubscriptionTracker tracker;
  NotificationService notifications;
  UserEventPublisher publisher;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    db = TestDb.fresh();
    alice = new Fixtures(db.jdbc(), time).human("alice");
    orders = mock(OrderQueries.class);
    accounts = mock(AccountQueries.class);
    messaging = new CapturingMessaging();
    tracker = new SubscriptionTracker(new RecordingMarketData(time), time);
    UserPush push = new UserPush(tracker, RealtimeTestSupport.provider(messaging));
    notifications = new NotificationService(db.jdbc(), new PublicId(time), time, JacksonConfig.newMapper(), push);
    publisher = new UserEventPublisher(orders, accounts, notifications, push, Runnable::run);
    when(accounts.account(7L)).thenReturn(TradingSamples.account("acc_7", "512.34"));
    when(accounts.account(8L)).thenReturn(TradingSamples.account("acc_8", "500.00"));
  }

  void connectAlice() {
    tracker.connect("s1", alice.id(), alice.publicId(), false);
  }

  List<NotificationDto> notes() {
    Page<NotificationDto> page = notifications.list(alice.id(), false, null, 50);
    return page.items();
  }

  @Test
  void anAcceptedOrderIsPushedWithoutTimelineAndTheAccountFollows() {
    connectAlice();
    OrderDto pending = TradingSamples.order("ord_1", "AAPL", Side.BUY, OrderType.LIMIT, OrderStatus.PENDING, "10",
        "0", null, null, null, null);
    when(orders.byPublicId(7L, "ord_1", false)).thenReturn(Optional.of(pending));

    publisher.on(new OrderChanged(7, alice.id(), "ord_1", OrderEventType.ACCEPTED, OrderStatus.PENDING,
        TestTimes.START));

    List<RealtimeTestSupport.Sent> sent = messaging.to("/queue/orders");
    assertThat(sent).singleElement().satisfies(s -> {
      assertThat(s.user()).isEqualTo(alice.publicId());
      OrderUpdateDto u = (OrderUpdateDto) s.payload();
      assertThat(u.event()).isEqualTo(OrderEventType.ACCEPTED);
      assertThat(u.order().orderId()).isEqualTo("ord_1");
      assertThat(u.execution()).isNull();
    });
    assertThat(messaging.to("/queue/account")).singleElement()
        .satisfies(s -> assertThat(((AccountDto) s.payload()).equity()).isEqualByComparingTo("512.34"));
    assertThat(notes()).as("no notification for an acceptance").isEmpty();
  }

  @Test
  void aFillCarriesTheLatestExecutionAndNotifiesOnce() {
    connectAlice();
    OrderDto filled = TradingSamples.order("ord_2", "AAPL", Side.BUY, OrderType.MARKET, OrderStatus.FILLED, "10",
        "10", "227.41", null,
        List.of(TradingSamples.execution("exe_a", "ord_2", "AAPL", Side.BUY, "4", "227.40", TestTimes.START),
            TradingSamples.execution("exe_b", "ord_2", "AAPL", Side.BUY, "6", "227.42",
                TestTimes.START.plusMillis(5))),
        List.of(TradingSamples.event(1, OrderEventType.ACCEPTED, OrderStatus.PENDING),
            TradingSamples.event(2, OrderEventType.WORKING, OrderStatus.WORKING),
            TradingSamples.event(3, OrderEventType.FILL, OrderStatus.FILLED)));
    when(orders.byPublicId(7L, "ord_2", true)).thenReturn(Optional.of(filled));
    OrderChanged event = new OrderChanged(7, alice.id(), "ord_2", OrderEventType.FILL, OrderStatus.FILLED,
        TestTimes.START);

    publisher.on(event);
    publisher.on(event);   // redelivered

    List<RealtimeTestSupport.Sent> sent = messaging.to("/queue/orders");
    assertThat(sent).hasSize(2);
    OrderUpdateDto u = (OrderUpdateDto) sent.get(0).payload();
    assertThat(u.execution().executionId()).isEqualTo("exe_b");
    assertThat(u.order().executions()).as("timeline stripped").isNull();
    assertThat(u.order().events()).isNull();

    assertThat(notes()).singleElement().satisfies(n -> {
      assertThat(n.kind()).isEqualTo(NotificationKind.FILL);
      assertThat(n.title()).isEqualTo("Bought 10 AAPL");
      assertThat(n.body()).contains("market order to buy 10 AAPL").contains("$227.41");
      assertThat(n.payload()).containsEntry("orderId", "ord_2").containsEntry("executionId", "exe_b");
    });
    assertThat(db.jdbc().sql("SELECT source_ref FROM notification").query(String.class).single())
        .isEqualTo("ORD:ord_2:3");
    assertThat(messaging.to("/queue/notifications")).hasSize(1);
  }

  @Test
  void offlinePlayersGetNotificationsButNoPushes() {
    OrderDto expired = TradingSamples.order("ord_3", "MSFT", Side.SELL, OrderType.LIMIT, OrderStatus.EXPIRED, "5",
        "2", "410.00", null, List.of(), List.of(TradingSamples.event(4, OrderEventType.EXPIRED,
            OrderStatus.EXPIRED)));
    when(orders.byPublicId(7L, "ord_3", true)).thenReturn(Optional.of(expired));

    publisher.on(new OrderChanged(7, alice.id(), "ord_3", OrderEventType.EXPIRED, OrderStatus.EXPIRED,
        TestTimes.START));

    assertThat(messaging.sent).isEmpty();
    verify(accounts, never()).account(anyLong());
    assertThat(notes()).singleElement().satisfies(n -> {
      assertThat(n.kind()).isEqualTo(NotificationKind.ORDER_EXPIRED);
      assertThat(n.title()).isEqualTo("Order expired: sell MSFT");
      assertThat(n.body()).contains("limit order to sell 5 MSFT at $227.40").contains("after filling 2");
    });
  }

  @Test
  void userCancelsAreQuietButSystemCancelsNotify() {
    OrderDto mine = TradingSamples.order("ord_4", "AAPL", Side.BUY, OrderType.LIMIT, OrderStatus.CANCELLED, "1",
        "0", null, "USER", null, List.of());
    OrderDto frozen = TradingSamples.order("ord_5", "AAPL", Side.BUY, OrderType.LIMIT, OrderStatus.CANCELLED, "1",
        "0", null, "PLAYER_FROZEN", null, List.of());
    when(orders.byPublicId(7L, "ord_4", true)).thenReturn(Optional.of(mine));
    when(orders.byPublicId(7L, "ord_5", true)).thenReturn(Optional.of(frozen));
    publisher.on(new OrderChanged(7, alice.id(), "ord_4", OrderEventType.CANCELLED, OrderStatus.CANCELLED,
        TestTimes.START));
    publisher.on(new OrderChanged(7, alice.id(), "ord_5", OrderEventType.CANCELLED, OrderStatus.CANCELLED,
        TestTimes.START));
    assertThat(notes()).singleElement().satisfies(n -> {
      assertThat(n.kind()).isEqualTo(NotificationKind.ORDER_CANCELLED);
      assertThat(n.body()).contains("your account was frozen");
    });
    assertThat(db.jdbc().sql("SELECT source_ref FROM notification").query(String.class).single())
        .as("no timeline event: type and instant stand in for the seq").startsWith("ORD:ord_5:CANCELLED:");
  }

  @Test
  void depositsWithdrawalsViolationsResetsAndFreezesNotify() {
    connectAlice();
    publisher.on(new CashTransferPosted(7, alice.id(), "xfr_1", "bat_1", TransferType.DEPOSIT,
        new BigDecimal("100.00"), "Welcome bonus", TestTimes.START));
    publisher.on(new CashTransferPosted(7, alice.id(), "xfr_2", "bat_2", TransferType.WITHDRAWAL,
        new BigDecimal("-25.50"), null, TestTimes.START));
    publisher.on(new CashTransferPosted(7, alice.id(), "xfr_0", "bat_0", TransferType.INITIAL,
        new BigDecimal("500.00"), null, TestTimes.START));
    publisher.on(new ViolationIssued(7, alice.id(), "vio_1", ViolationType.GFV, AccountRestriction.SETTLED_CASH_ONLY,
        TestTimes.START));
    // a reset publishes RESET for the closed run (7) and for the new one (8) at the same instant: one notification
    publisher.on(new AccountChanged(7, alice.id(), "RESET", TestTimes.START));
    publisher.on(new AccountChanged(8, alice.id(), "RESET", TestTimes.START));
    publisher.on(new AccountChanged(7, alice.id(), "FILL", TestTimes.START));
    publisher.on(new PlayerStatusChanged(alice.id(), PlayerStatus.FROZEN, TestTimes.START));
    publisher.on(new PlayerStatusChanged(alice.id(), PlayerStatus.DISABLED, TestTimes.START));

    assertThat(notes()).extracting(NotificationDto::kind).containsExactlyInAnyOrder(NotificationKind.DEPOSIT,
        NotificationKind.WITHDRAWAL, NotificationKind.GFV_ISSUED, NotificationKind.ACCOUNT_RESET,
        NotificationKind.RESTRICTION_CHANGED);
    assertThat(notes()).filteredOn(n -> n.kind() == NotificationKind.DEPOSIT).singleElement().satisfies(n -> {
      assertThat(n.title()).isEqualTo("Deposit: +$100.00");
      assertThat(n.body()).contains("Memo: Welcome bonus").contains("never change your return");
      assertThat(n.payload()).containsEntry("amount", "100.00");
    });
    assertThat(notes()).filteredOn(n -> n.kind() == NotificationKind.WITHDRAWAL).singleElement()
        .satisfies(n -> assertThat(n.title()).isEqualTo("Withdrawal: −$25.50"));
    assertThat(notes()).filteredOn(n -> n.kind() == NotificationKind.GFV_ISSUED).singleElement()
        .satisfies(n -> assertThat(n.body()).contains("settled cash for 90 days"));
    assertThat(messaging.to("/queue/account")).as("every account event pushes the account").hasSize(7);
  }

  @Test
  void stubsAndMissingOrdersAreSkippedQuietly() {
    connectAlice();
    when(orders.byPublicId(anyLong(), anyString(), anyBoolean()))
        .thenThrow(ApiException.notImplemented("OrderQueries.byPublicId"));
    when(accounts.account(eq(7L))).thenThrow(ApiException.notImplemented("AccountQueries.account"));
    publisher.on(new OrderChanged(7, alice.id(), "ord_9", OrderEventType.FILL, OrderStatus.FILLED, TestTimes.START));
    assertThat(messaging.sent).isEmpty();
    assertThat(notes()).isEmpty();
    verify(orders, times(1)).byPublicId(7L, "ord_9", true);
  }

  @Test
  void textFormatting() {
    assertThat(UserEventPublisher.money(new BigDecimal("1234.5"))).isEqualTo("$1,234.50");
    assertThat(UserEventPublisher.price(new BigDecimal("0.12345"))).isEqualTo("$0.1235");
    assertThat(UserEventPublisher.price(new BigDecimal("227.4"))).isEqualTo("$227.40");
    assertThat(UserEventPublisher.qty(new BigDecimal("0.50000"))).isEqualTo("0.5");
    assertThat(UserEventPublisher.signedMoney(new BigDecimal("-3"))).isEqualTo("−$3.00");
  }
}
