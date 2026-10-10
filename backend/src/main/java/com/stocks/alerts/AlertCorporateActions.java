package com.stocks.alerts;

import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.NotificationKind;
import com.stocks.common.events.CorporateActionApplied;
import com.stocks.common.time.TimeSource;
import com.stocks.realtime.NotificationService;
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
 * Keeps price alerts meaningful across corporate actions (§19.1), on the {@code eventsExecutor} (never the engine
 * thread):
 * <ul>
 *   <li>a forward or reverse split changes every price of the symbol, so its ACTIVE alerts are <b>paused</b> and each
 *       affected player gets one {@code PRICE_ALERT} notification asking them to check and resume;</li>
 *   <li>a name change keeps prices, so the symbol's ACTIVE and PAUSED alerts <b>follow the new ticker</b> (with a
 *       notification).</li>
 * </ul>
 * Idempotent: a redelivered event finds nothing left to pause or rename, and notifications are keyed by the action.
 */
@Component
public class AlertCorporateActions {

  private static final Logger log = LoggerFactory.getLogger(AlertCorporateActions.class);

  private final AlertRepository alerts;
  private final AlertMonitor monitor;
  private final NotificationService notifications;
  private final JdbcClient jdbc;
  private final TimeSource time;

  public AlertCorporateActions(AlertRepository alerts, AlertMonitor monitor, NotificationService notifications,
                               JdbcClient jdbc, TimeSource time) {
    this.alerts = alerts;
    this.monitor = monitor;
    this.notifications = notifications;
    this.jdbc = jdbc;
    this.time = time;
  }

  @Async("eventsExecutor")
  @EventListener
  public void on(CorporateActionApplied e) {
    handle(e);
  }

  void handle(CorporateActionApplied e) {
    if (e == null || e.symbol() == null || e.type() == null) {
      return;
    }
    try {
      if (e.type() == CorporateActionType.FORWARD_SPLIT || e.type() == CorporateActionType.REVERSE_SPLIT) {
        List<Long> players = alerts.pauseSymbol(e.symbol(), time.now());
        monitor.changed(e.symbol());
        String what = e.type() == CorporateActionType.FORWARD_SPLIT ? "a split" : "a reverse split";
        for (long player : players) {
          notify(player, e, e.symbol() + " alerts paused after " + what,
              e.symbol() + " had " + what + ", so its prices changed and your " + e.symbol() + " alerts were paused. "
                  + "Check their thresholds and resume them.", null);
        }
      } else if (e.type() == CorporateActionType.NAME_CHANGE) {
        String renamed = jdbc.sql("SELECT new_symbol FROM corporate_action WHERE public_id = ?").param(e.actionId())
            .query(String.class).optional().orElse(null);
        if (renamed == null || renamed.isBlank() || renamed.equals(e.symbol())) {
          return;
        }
        List<Long> players = alerts.renameSymbol(e.symbol(), renamed, time.now());
        monitor.changed(e.symbol());
        monitor.changed(renamed);
        for (long player : players) {
          notify(player, e, e.symbol() + " is now " + renamed,
              e.symbol() + " changed its ticker to " + renamed + "; your alerts now follow " + renamed + ".", renamed);
        }
      }
    } catch (RuntimeException ex) {
      log.warn("Price alerts after corporate action {} ({} {}) failed: {}", e.actionId(), e.type(), e.symbol(),
          ex.toString());
    }
  }

  private void notify(long playerId, CorporateActionApplied e, String title, String body, String newSymbol) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("symbol", newSymbol != null ? newSymbol : e.symbol());
    payload.put("corporateActionId", e.actionId());
    payload.put("type", e.type().name());
    notifications.notify(playerId, NotificationKind.PRICE_ALERT, title, body, payload, "ALERTCA:" + e.actionId());
  }
}
