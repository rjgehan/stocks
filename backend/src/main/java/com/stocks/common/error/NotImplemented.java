package com.stocks.common.error;

import java.util.function.Supplier;

/**
 * Calls into a contract that may still be a Day-0 stub (§27.1: every stub method throws
 * {@code ApiException(NOT_IMPLEMENTED)}). WS1's admin and identity code degrades gracefully while WS2–WS4 land: a
 * stubbed call yields the fallback, and every other failure (including a real {@code NOT_FOUND}) propagates.
 *
 * <pre>{@code
 * List<RunDto> runs = NotImplemented.orElse(() -> accountQueries.runs(playerId), List.of());
 * }</pre>
 */
public final class NotImplemented {

  private NotImplemented() {}

  /** True if {@code e} is the Day-0 stub signal (501 {@code NOT_IMPLEMENTED}). */
  public static boolean is(Throwable e) {
    return e instanceof ApiException api && api.code() == ErrorCode.NOT_IMPLEMENTED;
  }

  /** {@code call.get()}, or {@code fallback} if the callee is a Day-0 stub. */
  public static <T> T orElse(Supplier<T> call, T fallback) {
    try {
      return call.get();
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        return fallback;
      }
      throw e;
    }
  }

  /** Runs {@code call}; returns false (instead of throwing) if the callee is a Day-0 stub. */
  public static boolean run(Runnable call) {
    try {
      call.run();
      return true;
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        return false;
      }
      throw e;
    }
  }
}
