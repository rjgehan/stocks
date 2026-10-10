package com.stocks.trading.order.mapper;

import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.trading.dto.RefQuoteDto;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.store.ExecutionRow;

/** {@code execution} row → §18.3 {@code Execution} (REST, STOMP and MCP share it). */
public final class ExecutionDtoMapper {

  private ExecutionDtoMapper() {}

  public static ExecutionDto toDto(ExecutionRow e) {
    FeesDto fees = new FeesDto(e.feeSec(), e.feeTaf(), e.feeCat(), e.feesTotal());
    RefQuoteDto ref = new RefQuoteDto(e.refBid(), e.refAsk(), e.refBidSize(), e.refAskSize(), e.refLast(),
        e.refQuoteTs(), e.refConfirmedAt(), e.refSource(), e.refQuality(), e.refClamped());
    return new ExecutionDto(e.publicId(), e.orderPublicId(), e.symbol(), e.side(), OrderValidator.qtyOut(e.qty()),
        e.price(), e.principal(), fees, e.netAmount(), e.realizedPnl(), e.liquidity(), e.executedAt(), e.tradeDate(),
        e.settleDate(), ref);
  }
}
