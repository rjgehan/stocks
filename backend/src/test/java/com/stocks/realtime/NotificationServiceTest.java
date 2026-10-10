package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.NotificationKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.realtime.RealtimeTestSupport.CapturingMessaging;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sole writer of {@code notification} (§27.2): idempotent by {@code (player, source_ref)} through a cast-everything
 * MERGE (runs on H2 and, with {@code -Ppg-it}, on PostgreSQL 18.6, including the null payload), newest-first paging,
 * read marking, and the push to {@code /user/queue/notifications}.
 */
class NotificationServiceTest {

  MutableTimeSource time;
  TestDb db;
  Fixtures fx;
  CapturingMessaging messaging;
  SubscriptionTracker tracker;
  NotificationService notifications;
  Fixtures.PlayerRef alice;
  Fixtures.PlayerRef bob;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    db = TestDb.fresh();
    fx = new Fixtures(db.jdbc(), time);
    alice = fx.human("alice");
    bob = fx.human("bob");
    messaging = new CapturingMessaging();
    tracker = new SubscriptionTracker(new RecordingMarketData(time), time);
    UserPush push = new UserPush(tracker, RealtimeTestSupport.provider(messaging));
    notifications = new NotificationService(db.jdbc(), new PublicId(time), time, JacksonConfig.newMapper(), push);
  }

  @Test
  void aSourceRefNotifiesOnceAndOnlyConnectedPlayersArePushed() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", "ord_1");
    payload.put("amount", new BigDecimal("100.00"));
    Optional<NotificationDto> first = notifications.notify(alice.id(), NotificationKind.DEPOSIT, "Deposit: +$100.00",
        "The admin added cash.", payload, "XFER:xfr_1");
    assertThat(first).isPresent();
    assertThat(first.get().id()).startsWith("ntf_");
    assertThat(first.get().payload()).containsEntry("orderId", "ord_1").containsEntry("amount", "100.00");
    assertThat(messaging.sent).as("alice is not connected").isEmpty();

    tracker.connect("s1", alice.id(), alice.publicId(), false);
    assertThat(notifications.notify(alice.id(), NotificationKind.DEPOSIT, "again", "again", null, "XFER:xfr_1"))
        .as("same source_ref").isEmpty();
    assertThat(notifications.notify(bob.id(), NotificationKind.DEPOSIT, "bob", "bob", null, "XFER:xfr_1"))
        .as("another player, same ref").isPresent();
    Optional<NotificationDto> pushed = notifications.notify(alice.id(), NotificationKind.FILL, "Bought 1 AAPL",
        "Filled.", null, "ORD:ord_2:3");
    assertThat(pushed).isPresent();
    assertThat(pushed.get().payload()).isNull();
    assertThat(messaging.sent).singleElement().satisfies(s -> {
      assertThat(s.user()).isEqualTo(alice.publicId());
      assertThat(s.destination()).isEqualTo("/queue/notifications");
      assertThat(((NotificationDto) s.payload()).title()).isEqualTo("Bought 1 AAPL");
    });
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM notification").query(Long.class).single()).isEqualTo(3);
  }

  @Test
  void listIsNewestFirstWithCursorsAndUnreadFilter() {
    for (int i = 0; i < 5; i++) {
      notifications.notify(alice.id(), NotificationKind.FILL, "n" + i, "body", null, "REF:" + i);
      time.advance(Duration.ofSeconds(1));
    }
    notifications.notify(bob.id(), NotificationKind.FILL, "bob's", "body", null, "REF:0");

    Page<NotificationDto> page1 = notifications.list(alice.id(), false, null, 2);
    assertThat(page1.items()).extracting(NotificationDto::title).containsExactly("n4", "n3");
    assertThat(page1.nextCursor()).isNotNull();
    Page<NotificationDto> page2 = notifications.list(alice.id(), false, Cursor.parse(page1.nextCursor()), 2);
    assertThat(page2.items()).extracting(NotificationDto::title).containsExactly("n2", "n1");
    Page<NotificationDto> page3 = notifications.list(alice.id(), false, Cursor.parse(page2.nextCursor()), 2);
    assertThat(page3.items()).extracting(NotificationDto::title).containsExactly("n0");
    assertThat(page3.nextCursor()).isNull();

    String n3 = page1.items().get(1).id();
    assertThat(notifications.markRead(alice.id(), List.of(n3), null)).isEqualTo(1);
    assertThat(notifications.markRead(alice.id(), List.of(n3), null)).as("already read").isZero();
    assertThat(notifications.list(alice.id(), true, null, 50).items()).extracting(NotificationDto::title)
        .containsExactly("n4", "n2", "n1", "n0");
    assertThat(notifications.list(alice.id(), false, null, 50).items().get(1).readAt())
        .isEqualTo(TestTimes.START.plusSeconds(5));
    assertThat(notifications.unreadCount(alice.id())).isEqualTo(4);
  }

  @Test
  void markReadAllAndOtherPlayersIdsAreIgnored() {
    notifications.notify(alice.id(), NotificationKind.FILL, "a", "b", null, "R:1");
    NotificationDto bobs = notifications.notify(bob.id(), NotificationKind.FILL, "a", "b", null, "R:1").orElseThrow();
    assertThat(notifications.markRead(alice.id(), List.of(bobs.id()), false)).isZero();
    assertThat(notifications.unreadCount(bob.id())).isEqualTo(1);
    assertThat(notifications.markRead(alice.id(), null, true)).isEqualTo(1);
    assertThat(notifications.unreadCount(alice.id())).isZero();
  }

  @Test
  void markReadNeedsExactlyOneOfIdsAndAll() {
    assertThatThrownBy(() -> notifications.markRead(alice.id(), null, null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> notifications.markRead(alice.id(), List.of("ntf_x"), true))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> notifications.markRead(alice.id(), List.of(), false))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void textIsClippedAndOversizedPayloadsDropped() {
    String longTitle = "T".repeat(500);
    String longBody = "B\u0007".repeat(400) + "x".repeat(300);   // 700 characters once the BELs are stripped
    Map<String, Object> huge = Map.of("blob", "x".repeat(5000));
    NotificationDto n = notifications.notify(alice.id(), NotificationKind.SYSTEM, longTitle, longBody, huge, "BIG")
        .orElseThrow();
    assertThat(n.title()).hasSize(NotificationService.TITLE_MAX);
    assertThat(n.body()).hasSize(NotificationService.BODY_MAX).doesNotContain("\u0007");
    assertThat(n.payload()).isNull();
    assertThatThrownBy(() -> notifications.notify(alice.id(), NotificationKind.SYSTEM, "t", "b", null, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
