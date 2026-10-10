package com.stocks.contract.portfolio;

import com.stocks.common.domain.DataQuality;
import com.stocks.contract.marketdata.Mark;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * An account valued at {@code asOf} (§16.1). {@code marksQuality} is the worst quality among {@code marks}
 * (keyed by symbol). Units and unit value at scale 18.
 */
public record Valuation(long accountId, Instant asOf, BigDecimal cash, BigDecimal longMarketValue,
    BigDecimal dividendsReceivable, BigDecimal equity, BigDecimal netContributions, BigDecimal units,
    BigDecimal unitValue, int unitSeries, DataQuality marksQuality, Map<String, Mark> marks) {}
