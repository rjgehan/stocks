package com.stocks.trading.store;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One {@code execution} row (§8.1), with its order's public id. */
public record ExecutionRow(long id, String publicId, long orderId, String orderPublicId, long accountId,
                           String symbol, Side side, BigDecimal qty, BigDecimal price, BigDecimal principal,
                           BigDecimal feeSec, BigDecimal feeTaf, BigDecimal feeCat, BigDecimal netAmount,
                           BigDecimal realizedPnl, Liquidity liquidity, Instant executedAt, LocalDate tradeDate,
                           LocalDate settleDate, BigDecimal refBid, BigDecimal refAsk, Long refBidSize,
                           Long refAskSize, BigDecimal refLast, Instant refQuoteTs, Instant refConfirmedAt,
                           String refSource, DataQuality refQuality, boolean refClamped) {

  public BigDecimal feesTotal() {
    return feeSec.add(feeTaf).add(feeCat);
  }
}
