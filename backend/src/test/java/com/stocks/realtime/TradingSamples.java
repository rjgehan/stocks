package com.stocks.realtime;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import com.stocks.contract.trading.dto.RefQuoteDto;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Realistic trading DTOs (the §18.4 notional buy, fills, accounts) for realtime and MCP tests. */
public final class TradingSamples {

  private TradingSamples() {}

  public static OrderDto order(String orderId, String symbol, Side side, OrderType type, OrderStatus status,
                               String qty, String filledQty, String avgFillPrice, String statusReason,
                               List<ExecutionDto> executions, List<OrderEventDto> events) {
    Instant at = TestTimes.START;
    return new OrderDto(orderId, "client-" + orderId, "acc_1", symbol, side, type, dec(qty), null, false,
        type == OrderType.LIMIT ? new BigDecimal("227.40") : null, null, null, null, null, null, TimeInForce.DAY,
        false, status, null, statusReason, false, dec(filledQty == null ? "0" : filledQty),
        new BigDecimal("0.00"), dec(avgFillPrice), new BigDecimal("0.00"), new BigDecimal("0.00"), BigDecimal.ZERO,
        TestTimes.TRADE_DATE, at, at, at.plusMillis(600), null, null, at.plusSeconds(21600), null, null, null,
        Channel.MCP_KEY, "momentum", executions, events, null, false, null);
  }

  public static ExecutionDto execution(String executionId, String orderId, String symbol, Side side, String qty,
                                       String price, Instant at) {
    BigDecimal q = new BigDecimal(qty);
    BigDecimal p = new BigDecimal(price);
    BigDecimal principal = q.multiply(p).setScale(2, java.math.RoundingMode.HALF_UP);
    return new ExecutionDto(executionId, orderId, symbol, side, q, p, principal, FeesDto.zero(),
        side == Side.BUY ? principal.negate() : principal, null, Liquidity.TOUCH, at, TestTimes.TRADE_DATE,
        TestTimes.SETTLE_DATE, new RefQuoteDto(new BigDecimal("227.39"), new BigDecimal("227.41"), 300L, 200L,
            new BigDecimal("227.40"), at, at, "scripted", DataQuality.REALTIME_CONSOLIDATED, false));
  }

  public static OrderEventDto event(int seq, OrderEventType type, OrderStatus after) {
    return new OrderEventDto(seq, type, after, TestTimes.START.plusMillis(seq), null);
  }

  public static AccountDto account(String accountId, String equity) {
    BigDecimal e = new BigDecimal(equity);
    BigDecimal zero = new BigDecimal("0.00");
    return new AccountDto(accountId, 1, AccountType.CASH, AccountStatus.ACTIVE, AccountRestriction.NONE, null, null,
        e, zero, zero, new AccountDto.AccountCashDto(e, e, zero, e, e), zero, e, new BigDecimal("500.00"),
        new BigDecimal("500.00"), e.subtract(new BigDecimal("500.00")), new BigDecimal("0.0000000000"),
        new BigDecimal("1.000000000000000000"), zero, new BigDecimal("0.0000000000"),
        new AccountDto.AccountGfvDto(0, null, 3, 4), 0, TestTimes.START, DataQuality.SIMULATED, TestTimes.START);
  }

  static BigDecimal dec(String s) {
    return s == null ? null : new BigDecimal(s);
  }
}
