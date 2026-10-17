package com.stocks.alerts;

import com.stocks.alerts.AlertRepository.AlertRow;
import com.stocks.alerts.AlertRepository.NewAlert;
import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.AlertStatus;
import com.stocks.common.domain.Crypto;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.alerts.dto.AlertDto;
import com.stocks.contract.alerts.dto.CreateAlertRequest;
import com.stocks.contract.alerts.dto.UpdateAlertRequest;
import com.stocks.contract.marketdata.MarketDataService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Price alerts of one player (§18.2 {@code /alerts}, MCP {@code list_alerts}/{@code create_alert}/{@code delete_alert},
 * §19.1). Validates, enforces the per-player limits, keeps creation idempotent (an identical ACTIVE or PAUSED alert is
 * returned instead of a duplicate) and tells {@link AlertMonitor} about every change. Alerts never trade.
 */
@Service
public class AlertService {

  private static final Logger log = LoggerFactory.getLogger(AlertService.class);

  public static final int NOTE_MAX = 140;
  public static final Duration MAX_EXPIRY = Duration.ofDays(365);
  static final Pattern SYMBOL = Pattern.compile(Crypto.SYMBOL_REGEX);

  /** The result of a create: the alert, and whether it is new (201) or an identical existing one (200). */
  public record Created(AlertDto alert, boolean created) {}

  private final AlertRepository alerts;
  private final AlertMonitor monitor;
  private final MarketDataService marketData;
  private final JdbcClient jdbc;
  private final PublicId publicId;
  private final TimeSource time;
  private final AlertProperties props;
  private final Map<Long, Object> playerLocks = new ConcurrentHashMap<>();

  public AlertService(AlertRepository alerts, AlertMonitor monitor, MarketDataService marketData, JdbcClient jdbc,
                      PublicId publicId, TimeSource time, AlertProperties props) {
    this.alerts = alerts;
    this.monitor = monitor;
    this.marketData = marketData;
    this.jdbc = jdbc;
    this.publicId = publicId;
    this.time = time;
    this.props = props;
  }

  // ------------------------------------------------------------------------------------------- reads

  /** The player's alerts: ACTIVE, PAUSED, TRIGGERED, EXPIRED, newest first within each. */
  public List<AlertDto> list(long playerId, AlertStatus statusOrNull, String symbolOrNull) {
    String symbol = symbolOrNull == null || symbolOrNull.isBlank() ? null : normalize(symbolOrNull);
    return alerts.list(playerId, statusOrNull, symbol).stream().map(AlertService::dto).toList();
  }

  public AlertDto get(long playerId, String alertId) {
    return dto(require(playerId, alertId));
  }

  // ------------------------------------------------------------------------------------------- writes

  /** Creates an alert, or returns the identical ACTIVE/PAUSED one ({@link Created#created()} false). */
  public Created create(long playerId, CreateAlertRequest req) {
    if (req == null) {
      throw ApiException.validation("symbol", "symbol is required.");
    }
    String symbol = knownSymbol(req.symbol());
    AlertCondition condition = requireCondition(req.condition());
    BigDecimal threshold = AlertRules.validThreshold(condition, req.threshold());
    boolean repeating = Boolean.TRUE.equals(req.repeat());
    Instant now = time.now();
    Instant expiresAt = validExpiry(req.expiresAt(), now);
    String note = validNote(req.note());
    synchronized (lock(playerId)) {
      var same = alerts.findIdentical(playerId, symbol, condition, threshold, repeating);
      if (same.isPresent()) {
        return new Created(dto(same.get()), false);
      }
      requireRoom(playerId);
      alerts.trimFinished(playerId, Math.max(0, props.maxStoredPerPlayer() - props.maxActivePerPlayer()));
      AlertRow row = alerts.insert(new NewAlert(publicId.next(Prefix.ALERT), playerId, symbol, condition, threshold,
          repeating, note, expiresAt), now);
      monitor.changed(symbol);
      log.debug("Alert {} created for player {}: {} {} {}", row.publicId(), playerId, symbol, condition, threshold);
      return new Created(dto(row), true);
    }
  }

  /**
   * Replaces condition, threshold, repeat, expiry and note, and re-arms. A TRIGGERED or EXPIRED alert becomes ACTIVE
   * again (and counts toward the limit); a PAUSED one stays PAUSED.
   */
  public AlertDto update(long playerId, String alertId, UpdateAlertRequest req) {
    if (req == null) {
      throw ApiException.validation("condition", "condition is required.");
    }
    AlertCondition condition = requireCondition(req.condition());
    BigDecimal threshold = AlertRules.validThreshold(condition, req.threshold());
    boolean repeating = Boolean.TRUE.equals(req.repeat());
    Instant now = time.now();
    Instant expiresAt = validExpiry(req.expiresAt(), now);
    String note = validNote(req.note());
    synchronized (lock(playerId)) {
      AlertRow row = require(playerId, alertId);
      AlertStatus next = row.status() == AlertStatus.PAUSED ? AlertStatus.PAUSED : AlertStatus.ACTIVE;
      if (!row.status().isLive()) {
        requireRoom(playerId);
      }
      alerts.update(row.id(), condition, threshold, repeating, expiresAt, note, next, now);
      monitor.changed(row.symbol());
      return dto(alerts.byPublicId(playerId, alertId).orElseThrow());
    }
  }

  /** ACTIVE → PAUSED (a paused alert is kept but never evaluated). Idempotent for a PAUSED alert. */
  public AlertDto pause(long playerId, String alertId) {
    synchronized (lock(playerId)) {
      AlertRow row = require(playerId, alertId);
      if (row.status() == AlertStatus.ACTIVE) {
        alerts.transition(row.id(), AlertStatus.ACTIVE, AlertStatus.PAUSED, time.now());
        monitor.changed(row.symbol());
      } else if (row.status() != AlertStatus.PAUSED) {
        throw ApiException.validation("status", "Only an active alert can be paused; edit it to turn it back on.");
      }
      return dto(alerts.byPublicId(playerId, alertId).orElseThrow());
    }
  }

  /** PAUSED → ACTIVE, re-armed. Idempotent for an ACTIVE alert. */
  public AlertDto resume(long playerId, String alertId) {
    synchronized (lock(playerId)) {
      AlertRow row = require(playerId, alertId);
      if (row.status() == AlertStatus.PAUSED) {
        Instant now = time.now();
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
          throw ApiException.validation("expiresAt", "This alert has expired; edit it to set a new expiry.");
        }
        alerts.transition(row.id(), AlertStatus.PAUSED, AlertStatus.ACTIVE, now);
        monitor.changed(row.symbol());
      } else if (row.status() != AlertStatus.ACTIVE) {
        throw ApiException.validation("status", "Only a paused alert can be resumed; edit it to turn it back on.");
      }
      return dto(alerts.byPublicId(playerId, alertId).orElseThrow());
    }
  }

  /** Deletes the alert; false when the player has no such alert. */
  public boolean delete(long playerId, String alertId) {
    synchronized (lock(playerId)) {
      var row = alerts.byPublicId(playerId, alertId == null ? "" : alertId.strip());
      if (row.isEmpty()) {
        return false;
      }
      alerts.delete(playerId, row.get().publicId());
      monitor.changed(row.get().symbol());
      return true;
    }
  }

  // ------------------------------------------------------------------------------------------- mapping

  /** §18.3 {@code Alert}: a price threshold at scale 4, a change threshold (a fraction) at scale 6. */
  public static AlertDto dto(AlertRow r) {
    BigDecimal threshold = r.condition().isPrice()
        ? r.threshold().setScale(AlertRules.PRICE_DECIMALS, RoundingMode.HALF_EVEN)
        : r.threshold().setScale(AlertRules.CHANGE_DECIMALS, RoundingMode.HALF_EVEN);
    return new AlertDto(r.publicId(), r.symbol(), r.condition(), threshold, r.repeating(), r.status(), r.armed(),
        r.note(), r.expiresAt(), r.triggerCount(), r.lastTriggeredAt(), r.lastTriggerPrice(), r.createdAt(),
        r.updatedAt());
  }

  // ------------------------------------------------------------------------------------------- validation

  private AlertRow require(long playerId, String alertId) {
    if (alertId == null || alertId.isBlank()) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No such alert.");
    }
    return alerts.byPublicId(playerId, alertId.strip())
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No such alert."));
  }

  private void requireRoom(long playerId) {
    if (alerts.countLive(playerId) >= props.maxActivePerPlayer()) {
      throw ApiException.of(ErrorCode.TOO_MANY_ALERTS,
              "You already have " + props.maxActivePerPlayer() + " active or paused alerts.")
          .withHint("Delete an alert you no longer need, then try again.");
    }
  }

  private static AlertCondition requireCondition(AlertCondition condition) {
    if (condition == null) {
      throw ApiException.validation("condition", "condition is required (PRICE_GTE, PRICE_LTE, CHANGE_PCT_GTE or "
          + "CHANGE_PCT_LTE).");
    }
    return condition;
  }

  static String normalize(String symbol) {
    return symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
  }

  /** Upper-cased and known (market data or the {@code asset} table), else {@code SYMBOL_NOT_FOUND}. */
  String knownSymbol(String raw) {
    String symbol = Crypto.fromPath(normalize(raw));
    if (symbol.isEmpty()) {
      throw ApiException.validation("symbol", "symbol is required.");
    }
    if (!SYMBOL.matcher(symbol).matches()) {
      throw ApiException.validation("symbol", "'" + Text.clip(raw, 20) + "' is not a ticker symbol.");
    }
    boolean known;
    try {
      known = marketData.asset(symbol).isPresent();
    } catch (RuntimeException e) {
      log.debug("asset({}) failed: {}", symbol, e.toString());
      known = false;
    }
    if (!known) {
      known = jdbc.sql("SELECT COUNT(*) FROM asset WHERE symbol = ?").param(symbol).query(Long.class).single() > 0;
    }
    if (!known) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol " + symbol + ".")
          .withField("symbol", ErrorCode.SYMBOL_NOT_FOUND, symbol);
    }
    return symbol;
  }

  private static Instant validExpiry(Instant expiresAt, Instant now) {
    if (expiresAt == null) {
      return null;
    }
    if (!expiresAt.isAfter(now)) {
      throw ApiException.validation("expiresAt", "expiresAt must be in the future.");
    }
    if (expiresAt.isAfter(now.plus(MAX_EXPIRY))) {
      throw ApiException.validation("expiresAt", "expiresAt can be at most 365 days away.");
    }
    return expiresAt;
  }

  private static String validNote(String note) {
    String clean = Text.trimToNull(Text.stripControl(note));
    if (clean != null && clean.length() > NOTE_MAX) {
      throw ApiException.validation("note", "A note has at most " + NOTE_MAX + " characters.");
    }
    return clean;
  }

  private Object lock(long playerId) {
    return playerLocks.computeIfAbsent(playerId, k -> new Object());
  }
}
