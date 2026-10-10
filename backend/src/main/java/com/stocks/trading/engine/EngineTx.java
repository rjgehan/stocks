package com.stocks.trading.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * What one engine transaction defers until it commits (§4 rule 2): application events to publish and in-memory
 * updates ({@link OpenOrderIndex}, leases). If the transaction rolls back, none of it happens, so memory never runs
 * ahead of the database.
 */
public final class EngineTx {

  private final List<Object> events = new ArrayList<>();
  private final List<Runnable> afterCommit = new ArrayList<>();

  public void publish(Object event) {
    events.add(event);
  }

  public void afterCommit(Runnable action) {
    afterCommit.add(action);
  }

  List<Object> events() {
    return events;
  }

  List<Runnable> afterCommitActions() {
    return afterCommit;
  }
}
