package com.stocks.marketdata.config;

import com.stocks.common.events.AdminAlertRaised;
import com.stocks.common.events.CorporateActionApplied;
import org.springframework.context.event.EventListener;

/**
 * Drops market data's corporate-action caches (holds and split adjustments, 10 s TTL each) when the engine has
 * committed an applied corporate action, or a corporate action was flagged for the admin (§14). Without it, a split
 * the admin resolves while its symbol is on hold is valued for up to 10 s at the doubled quantity times the held,
 * unadjusted mark (a fake +100% on the live board), and an automatic 03:45 split can be read without its adjustment.
 * Synchronous: engine events are published on the engine thread after commit, before the command returns.
 */
public class CorporateActionCacheListener {

  private final MarketDataRuntime runtime;

  public CorporateActionCacheListener(MarketDataRuntime runtime) {
    this.runtime = runtime;
  }

  @EventListener
  public void on(CorporateActionApplied e) {
    runtime.corporateActionsChanged();
  }

  @EventListener
  public void on(AdminAlertRaised e) {
    runtime.corporateActionsChanged();
  }
}
