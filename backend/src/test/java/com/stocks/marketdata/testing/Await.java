package com.stocks.marketdata.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls a condition (stream tests run on real sockets and threads). */
public final class Await {

  private Await() {}

  public static void until(Duration timeout, BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        throw new AssertionError("Timed out after " + timeout + " waiting for " + what);
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
  }

  public static void until(BooleanSupplier condition, String what) {
    until(Duration.ofSeconds(5), condition, what);
  }
}
