package com.stocks.realtime;

import com.stocks.common.domain.NotificationKind;
import com.stocks.common.events.AdminAlertRaised;
import com.stocks.common.events.CorporateActionEffect;
import com.stocks.contract.realtime.dto.AdminAlertDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Corporate actions (§14) for the people they affect, on the {@code eventsExecutor} (never the engine thread):
 * <ul>
 *   <li>{@link CorporateActionEffect} → a {@code CORPORATE_ACTION} notification for the player (split, rename,
 *       dividend accrued or paid), idempotent by {@code CA:<action>:<account>:<effect>};</li>
 *   <li>{@link AdminAlertRaised} → {@code AdminAlertDto} on {@code /user/queue/admin} of every connected admin (§19),
 *       e.g. a corporate action that needs review (it is also counted on the System page).</li>
 * </ul>
 */
@Component
public class CorporateActionNotifier {

  private static final Logger log = LoggerFactory.getLogger(CorporateActionNotifier.class);

  private final NotificationService notifications;
  private final UserPush push;
  private final JdbcClient jdbc;

  public CorporateActionNotifier(NotificationService notifications, UserPush push, JdbcClient jdbc) {
    this.notifications = notifications;
    this.push = push;
    this.jdbc = jdbc;
  }

  @Async("eventsExecutor")
  @EventListener
  public void on(CorporateActionEffect e) {
    handle(e);
  }

  @Async("eventsExecutor")
  @EventListener
  public void on(AdminAlertRaised e) {
    handle(e);
  }

  void handle(CorporateActionEffect e) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("corporateActionId", e.actionId());
      payload.put("type", e.type() == null ? null : e.type().name());
      payload.put("symbol", e.symbol());
      payload.put("effect", e.effect());
      notifications.notify(e.playerId(), NotificationKind.CORPORATE_ACTION, e.title(), e.body(), payload,
          "CA:" + e.actionId() + ":" + e.accountId() + ":" + e.effect());
    } catch (RuntimeException ex) {
      log.warn("Corporate-action notification {} for player {} failed: {}", e.actionId(), e.playerId(), ex.toString());
    }
  }

  void handle(AdminAlertRaised e) {
    AdminAlertDto.Level level;
    try {
      level = AdminAlertDto.Level.valueOf(e.level());
    } catch (RuntimeException ex) {
      level = AdminAlertDto.Level.WARN;
    }
    AdminAlertDto dto = new AdminAlertDto(level, e.title(), e.body(), e.at());
    try {
      List<Long> admins = jdbc.sql("SELECT id FROM player WHERE role = 'ADMIN' AND status = 'ACTIVE' ORDER BY id")
          .query(Long.class).list();
      for (long admin : admins) {
        push.toPlayer(admin, StompDestinations.ADMIN_QUEUE, dto);
      }
    } catch (RuntimeException ex) {
      log.warn("Admin alert '{}' failed: {}", e.title(), ex.toString());
    }
  }
}
