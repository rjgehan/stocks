package com.stocks.trading.corpactions;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AdminAlertRaised;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.trading.CorporateActionResolution;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.trading.dto.CorporateActionDto;
import com.stocks.contract.trading.dto.CorporateActionOrigin;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * §14 split guard hand-off and the admin's resolution of corporate actions (§21.1).
 * <ul>
 *   <li>{@link #flagDetectedSplit} (WS2 {@code SplitGuard}, its own thread): idempotent per (symbol, ex-date). An
 *       APPLIED split or an earlier DETECTED row is returned as is (an admin SKIP sticks); a provider split still
 *       PENDING (its 03:45 application failed) becomes NEEDS_ADMIN; otherwise a DETECTED NEEDS_ADMIN row is created.
 *       Either way the symbol is on a corporate-action hold until the admin resolves it, and the admin is alerted.</li>
 *   <li>{@link #resolve}: one {@code ResolveCorporateActionCmd} on the engine (WS1's controller audits it).</li>
 * </ul>
 */
@Service
public class CorporateActionServiceImpl implements CorporateActionService {

  private static final Logger log = LoggerFactory.getLogger(CorporateActionServiceImpl.class);

  private final CorporateActionStore store;
  private final CorporateActionQueriesImpl queries;
  private final EngineCalls engine;
  private final PublicId publicIds;
  private final TimeSource time;
  private final ApplicationEventPublisher events;

  public CorporateActionServiceImpl(CorporateActionStore store, CorporateActionQueriesImpl queries, EngineCalls engine,
                                    PublicId publicIds, TimeSource time, ApplicationEventPublisher events) {
    this.store = store;
    this.queries = queries;
    this.engine = engine;
    this.publicIds = publicIds;
    this.time = time;
    this.events = events;
  }

  @Override
  public synchronized CorporateActionDto flagDetectedSplit(String symbol, LocalDate exDate, BigDecimal inferredOldRate,
                                                           BigDecimal inferredNewRate) {
    String sym = CorporateActionApplier.normalize(symbol);
    if (sym == null || exDate == null || !CorporateActionApplier.validRates(inferredOldRate, inferredNewRate)) {
      throw ApiException.validation("rates", "symbol, exDate and positive, different rates are required");
    }
    BigDecimal oldRate = CorporateActionStore.normalizeRate(inferredOldRate);
    BigDecimal newRate = CorporateActionStore.normalizeRate(inferredNewRate);
    String label = CorporateActionRow.plain(newRate) + "-for-" + CorporateActionRow.plain(oldRate);
    List<CorporateActionRow> known = store.splitsOn(sym, exDate);
    Optional<CorporateActionRow> settled = known.stream()
        .filter(r -> r.status() == CorporateActionStatus.APPLIED || r.origin() == CorporateActionOrigin.DETECTED)
        .findFirst();
    if (settled.isPresent()) {
      return queries.dto(settled.get());
    }
    Instant now = time.now();
    String why = "SplitGuard: raw and split-adjusted prior closes differ (about " + label + ")";
    for (CorporateActionRow r : known) {
      if (r.status() == CorporateActionStatus.PENDING && store.transition(r.id(), CorporateActionStatus.PENDING,
          CorporateActionStatus.NEEDS_ADMIN, CorporateActionStore.appendDetail(r.detail(),
              why + " before it was applied"), null, null)) {
        alert(r.symbol(), r.publicId(), exDate, why, now);
        return queries.dto(store.byId(r.id()).orElseThrow());
      }
      if (r.status() == CorporateActionStatus.NEEDS_ADMIN) {
        return queries.dto(r);                     // already waiting for the admin
      }
    }
    CorporateActionType type = newRate.compareTo(oldRate) > 0 ? CorporateActionType.FORWARD_SPLIT
        : CorporateActionType.REVERSE_SPLIT;
    String publicId = publicIds.next(Prefix.CORPORATE_ACTION);
    long id = store.insert(new CorporateActionStore.NewAction(publicId, null, sym, type, exDate, null, null, oldRate,
        newRate, null, null, CorporateActionStatus.NEEDS_ADMIN, CorporateActionOrigin.DETECTED, why, now));
    alert(sym, publicId, exDate, why, now);
    log.warn("Detected split {} {} on {} ({}): on hold until the admin resolves it", publicId, sym, exDate, label);
    return queries.dto(store.byId(id).orElseThrow());
  }

  @Override
  public CorporateActionDto resolve(String actionId, CorporateActionResolution resolution, BigDecimal oldRate,
                                    BigDecimal newRate, String note, long actorPlayerId) {
    if (resolution == null) {
      throw ApiException.validation("resolution", "APPLY or SKIP");
    }
    if (store.byPublicId(actionId).isEmpty()) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No corporate action " + actionId + ".");
    }
    CorporateActionRow row = engine.call(new EngineCommand.ResolveCorporateActionCmd(actionId, resolution, oldRate,
        newRate, note, actorPlayerId));
    return queries.dto(row);
  }

  private void alert(String symbol, String publicId, LocalDate exDate, String why, Instant now) {
    try {
      events.publishEvent(new AdminAlertRaised("WARN", "Split needs review: " + symbol,
          publicId + " (ex-date " + exDate + "): " + why + ". The symbol is on hold until you apply or skip it.",
          now));
    } catch (RuntimeException e) {
      log.warn("Admin alert for {} failed: {}", publicId, e.toString());
    }
  }
}
