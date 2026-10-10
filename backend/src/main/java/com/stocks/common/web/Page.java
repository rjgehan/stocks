package com.stocks.common.web;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results: {@code { "items": [...], "nextCursor": "opaque-or-null" }} (§6.6, §18.3 {@code Page<T>}).
 * Default limit 50, maximum 200, newest first unless stated otherwise.
 */
public record Page<T>(List<T> items, String nextCursor) {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  public Page {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public static <T> Page<T> empty() {
    return new Page<>(List.of(), null);
  }

  /** Clamps a requested limit: null or &lt; 1 → 50, &gt; 200 → 200. */
  public static int clampLimit(Integer requested) {
    if (requested == null || requested < 1) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  /**
   * Builds a page from a query that fetched up to {@code limit + 1} rows: if the extra row is present it is dropped
   * and {@code nextCursor} is derived from the last row kept.
   */
  public static <T> Page<T> fromLookahead(List<T> rows, int limit, Function<T, Cursor> cursorOf) {
    if (rows.size() <= limit) {
      return new Page<>(rows, null);
    }
    List<T> kept = rows.subList(0, limit);
    return new Page<>(kept, cursorOf.apply(kept.get(kept.size() - 1)).token());
  }

  public <R> Page<R> map(Function<T, R> mapper) {
    return new Page<>(items.stream().map(mapper).toList(), nextCursor);
  }
}
