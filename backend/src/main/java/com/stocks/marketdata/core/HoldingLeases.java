package com.stocks.marketdata.core;

import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.marketdata.LeasePriority;
import java.time.Duration;
import java.util.Set;

/**
 * {@code HOLDING_LEASES} (§15.5): every 60 s, every symbol any account holds gets a {@code HOLDING} lease (owner
 * {@code hold:<sym>}) so the poller keeps it fresh and the streams may carry it; symbols no longer held are released.
 */
public class HoldingLeases implements PeriodicTask {

  private final TrackedSymbols tracked;
  private final SubscriptionBudgetManager budget;
  private volatile Set<String> current = Set.of();

  public HoldingLeases(TrackedSymbols tracked, SubscriptionBudgetManager budget) {
    this.tracked = tracked;
    this.budget = budget;
  }

  @Override
  public String name() {
    return "HOLDING_LEASES";
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(60);
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public void tick() {
    Set<String> held = tracked.held();
    for (String s : current) {
      if (!held.contains(s)) {
        budget.release(s, "hold:" + s);
      }
    }
    for (String s : held) {
      budget.lease(s, LeasePriority.HOLDING, "hold:" + s);
    }
    current = Set.copyOf(held);
  }
}
