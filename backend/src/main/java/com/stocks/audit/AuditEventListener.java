package com.stocks.audit;

import com.stocks.common.events.CorporateActionApplied;
import com.stocks.common.events.ViolationIssued;
import com.stocks.contract.security.RequestMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * The {@code ACCOUNT} audit rows that originate in the engine (§17.7): a GFV issued (with the restriction after it)
 * and a corporate action applied. Published after commit by WS3; handled on the {@code eventsExecutor}, never on the
 * engine thread. Resets, restrictions set by the admin and money movements are audited where the admin acts
 * ({@code admin.*}), with the request's metadata.
 */
@Component
public class AuditEventListener {

  private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

  private final AuditService audit;

  public AuditEventListener(AuditService audit) {
    this.audit = audit;
  }

  @Async("eventsExecutor")
  @EventListener
  public void on(ViolationIssued e) {
    write(AuditEntry.of(AuditCategory.ACCOUNT, "GFV_ISSUED", AuditOutcome.OK)
        .withMeta(RequestMeta.system(null)).withTarget("violation", e.violationId())
        .withDetail("playerId", e.playerId()).withDetail("accountId", e.accountId())
        .withDetail("type", e.type() == null ? null : e.type().name())
        .withDetail("restrictionAfter", e.restrictionAfter() == null ? null : e.restrictionAfter().name()));
  }

  @Async("eventsExecutor")
  @EventListener
  public void on(CorporateActionApplied e) {
    write(AuditEntry.of(AuditCategory.ACCOUNT, "CORPORATE_ACTION_APPLIED", AuditOutcome.OK)
        .withMeta(RequestMeta.system(null)).withTarget("corporate_action", e.actionId())
        .withDetail("symbol", e.symbol()).withDetail("type", e.type() == null ? null : e.type().name()));
  }

  private void write(AuditEntry entry) {
    try {
      audit.record(entry);
    } catch (RuntimeException ex) {
      log.warn("Audit of {} failed: {}", entry.action(), ex.toString());
    }
  }
}
