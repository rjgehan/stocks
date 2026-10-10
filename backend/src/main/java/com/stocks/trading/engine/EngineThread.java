package com.stocks.trading.engine;

import java.util.function.Supplier;

/**
 * Marks the one thread allowed to write the engine tables (§4 rule 1, D2): {@code engine-main}, or the calling thread
 * while a test's {@code DirectEngineExecutor} runs a command. Every writer method calls {@link #assertCurrent()}.
 */
public final class EngineThread {

  public static final String NAME = "engine-main";

  private static final ThreadLocal<Boolean> ENGINE = ThreadLocal.withInitial(() -> Boolean.FALSE);

  private EngineThread() {}

  public static boolean isCurrent() {
    return ENGINE.get();
  }

  /** Throws unless the caller is the engine thread (§5.2 rule 1). */
  public static void assertCurrent() {
    if (!isCurrent()) {
      throw new IllegalStateException("Engine writers run only on " + NAME + " (called from "
          + Thread.currentThread().getName() + ")");
    }
  }

  /** Called once by the {@code engine-main} thread itself. */
  static void markCurrentThread() {
    ENGINE.set(Boolean.TRUE);
  }

  /**
   * Runs {@code body} as the engine. Only {@link EngineRunner}'s thread and a test's {@code DirectEngineExecutor}
   * (§12.10) may call this; production code submits commands instead.
   */
  public static <T> T runAsEngine(Supplier<T> body) {
    boolean previous = ENGINE.get();
    ENGINE.set(Boolean.TRUE);
    try {
      return body.get();
    } finally {
      ENGINE.set(previous);
    }
  }
}
