package com.stocks.marketdata.core;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.SplitAdjustments;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Split adjustment of reference prices (§14, table {@code price_adjustment}). {@link #record} runs inside the engine's
 * {@code ApplyCorporateActionCmd} transaction (the {@link JdbcClient} joins it); {@link #adjust} scales a price dated
 * before one or more applied splits to {@code asOfDate}:
 * {@code (price × Π old_rate).divide(Π new_rate, 4, HALF_EVEN)} ({@code Rounding.SPLIT_ADJUST}), multiplying first and
 * dividing last so 1-for-3 never throws. The table is small and cached for a few seconds.
 */
public class SplitAdjustmentsImpl implements SplitAdjustments {

  record Split(LocalDate exDate, BigDecimal oldRate, BigDecimal newRate) {}

  static final Duration CACHE_TTL = Duration.ofSeconds(10);

  private final JdbcClient jdbc;
  private final TimeSource time;
  private volatile Map<String, List<Split>> cache;
  private volatile Instant loadedAt;

  public SplitAdjustmentsImpl(DataSource dataSource, TimeSource time) {
    this.jdbc = JdbcClient.create(dataSource);
    this.time = time;
  }

  @Override
  public void record(String symbol, LocalDate exDate, BigDecimal oldRate, BigDecimal newRate, long corporateActionId) {
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(exDate, "exDate");
    if (oldRate == null || newRate == null || oldRate.signum() <= 0 || newRate.signum() <= 0) {
      throw new IllegalArgumentException("split rates must be > 0: " + oldRate + " -> " + newRate);
    }
    jdbc.sql("""
            MERGE INTO price_adjustment p
            USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS DATE), CAST(? AS NUMERIC(19,8)), CAST(? AS NUMERIC(19,8)),
                           CAST(? AS BIGINT), CAST(? AS TIMESTAMP WITH TIME ZONE)))
                  AS v(symbol, ex_date, old_rate, new_rate, corporate_action_id, created_at)
            ON p.symbol = v.symbol AND p.ex_date = v.ex_date
            WHEN MATCHED THEN UPDATE SET old_rate = v.old_rate, new_rate = v.new_rate,
                 corporate_action_id = v.corporate_action_id
            WHEN NOT MATCHED THEN INSERT (symbol, ex_date, old_rate, new_rate, corporate_action_id, created_at)
                 VALUES (v.symbol, v.ex_date, v.old_rate, v.new_rate, v.corporate_action_id, v.created_at)""")
        .params(symbol, exDate, oldRate, newRate, corporateActionId, Jdbc.ts(time.now())).update();
    cache = null;
  }

  @Override
  public BigDecimal adjust(String symbol, BigDecimal price, LocalDate priceDate, LocalDate asOfDate) {
    if (price == null || priceDate == null || asOfDate == null || !priceDate.isBefore(asOfDate)) {
      return price;
    }
    List<Split> splits = splits().getOrDefault(symbol, List.of());
    BigDecimal num = BigDecimal.ONE;
    BigDecimal den = BigDecimal.ONE;
    boolean any = false;
    for (Split s : splits) {
      if (priceDate.isBefore(s.exDate()) && !s.exDate().isAfter(asOfDate)) {
        num = num.multiply(s.oldRate());
        den = den.multiply(s.newRate());
        any = true;
      }
    }
    return any ? Rounding.SPLIT_ADJUST.divide(price.multiply(num), den) : price;
  }

  /** True when an applied split of {@code symbol} has {@code ex_date = date} (SplitGuard). */
  public boolean appliedOn(String symbol, LocalDate date) {
    return splits().getOrDefault(symbol, List.of()).stream().anyMatch(s -> s.exDate().equals(date));
  }

  private Map<String, List<Split>> splits() {
    Map<String, List<Split>> c = cache;
    Instant at = loadedAt;
    Instant now = time.now();
    if (c == null || at == null || Duration.between(at, now).compareTo(CACHE_TTL) > 0 || now.isBefore(at)) {
      Map<String, List<Split>> fresh = new HashMap<>();
      jdbc.sql("SELECT symbol, ex_date, old_rate, new_rate FROM price_adjustment ORDER BY symbol, ex_date")
          .query((rs, n) -> {
            fresh.computeIfAbsent(rs.getString("symbol"), k -> new ArrayList<>()).add(new Split(
                Jdbc.date(rs, "ex_date"), rs.getBigDecimal("old_rate"), rs.getBigDecimal("new_rate")));
            return null;
          }).list();
      c = Map.copyOf(fresh);
      cache = c;
      loadedAt = now;
    }
    return c;
  }
}
