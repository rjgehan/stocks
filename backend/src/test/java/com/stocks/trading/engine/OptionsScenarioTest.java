package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.trading.options.OptionExpiryJob;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Long options on the engine (§30): buys fill at the ask and sells at the bid in one piece, with principal = price ×
 * 100 × contracts and no fees; positions are valued at the mid × 100; option orders are whole-contract MARKET/LIMIT
 * DAY orders in the regular session only; an absurd spread gives no fill; expiration cash-settles in-the-money
 * contracts at intrinsic value from the underlying's close and expires the rest worthless, once.
 */
class OptionsScenarioTest extends TradingTestBase {

  static final LocalDate EXPIRATION = LocalDate.of(2026, 10, 16);   // the Friday after TestTimes.START

  @Autowired SettingsService settings;
  @Autowired OptionExpiryJob expiryJob;

  private Long admin;

  @AfterEach
  void optionsBackOn() {
    if (admin != null) {
      settings.put("options.enabled", "true", admin);
    }
  }

  /** A fresh call or put on a fresh underlying (letters only: an OCC root has no digits). */
  private String contract(String right, String strike) {
    String root = "OP" + letters(next());
    md.withAsset(root);
    String occ = root + "261016" + right + String.format("%08d", new BigDecimal(strike).movePointRight(3)
        .intValueExact());
    md.withAsset(new Asset(occ, root + " 16 Oct 26 $" + strike + (right.equals("C") ? " Call" : " Put"), "OPRA",
        AssetType.OPTION, true, false, false, false, false, LiquidityTier.SMALL, true));
    return occ;
  }

  private static String letters(int n) {
    StringBuilder s = new StringBuilder();
    int v = n;
    do {
      s.append((char) ('A' + v % 26));
      v /= 26;
    } while (v > 0);
    return s.toString();
  }

  private OrderDto fill(Player p, PlaceOrderRequest req, String occ, String bid, String ask) {
    OrderDto o = place(p, req).order();
    time.advance(Duration.ofSeconds(1));
    quote(occ, bid, 10, ask, 10);
    step();
    return order(p, o.orderId());
  }

  private static void rejected(Runnable r, ErrorCode code) {
    assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(code));
  }

  private PositionDto position(Player p, String symbol) {
    return accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(symbol)).findFirst()
        .orElse(null);
  }

  @Test
  void buyToOpenFillsAtTheAskTimes100AndSellToCloseAtTheBidSettlingNextDay() {
    Player p = human("10000.00");
    String occ = contract("C", "100");
    quote(occ, "2.40", 10, "2.50", 10);

    OrderDto placed = place(p, market(cid(), occ, Side.BUY, "2")).order();
    assertThat(placed.reservedCash()).isEqualByComparingTo("510.00");          // 2 × 2.50 × 1.02 × 100
    time.advance(Duration.ofSeconds(1));
    quote(occ, "2.40", 10, "2.50", 10);
    step();
    OrderDto bought = order(p, placed.orderId());
    assertThat(bought.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(bought.executions()).hasSize(1);                                 // one fill for every contract
    ExecutionDto buy = bought.executions().get(0);
    assertThat(buy.qty()).isEqualByComparingTo("2");
    assertThat(buy.price()).isEqualByComparingTo("2.50");                       // per share, at the ask
    assertThat(buy.principal()).isEqualByComparingTo("500.00");                 // 2.50 × 100 × 2
    assertThat(buy.netAmount()).isEqualByComparingTo("-500.00");                // no fees on options
    assertThat(account(p).cash().total()).isEqualByComparingTo("9500.00");

    PositionDto pos = position(p, occ);
    assertThat(pos.qty()).isEqualByComparingTo("2");
    assertThat(pos.avgCost()).isEqualByComparingTo("2.50");                     // per share
    assertThat(pos.costBasis()).isEqualByComparingTo("500.00");
    assertThat(pos.mark()).isEqualByComparingTo("2.45");                        // the mid
    assertThat(pos.marketValue()).isEqualByComparingTo("490.00");               // 2.45 × 100 × 2
    assertThat(pos.unrealizedPnl()).isEqualByComparingTo("-10.00");
    assertThat(account(p).equity()).isEqualByComparingTo("9990.00");

    OrderDto sold = fill(p, market(cid(), occ, Side.SELL, "2"), occ, "2.60", "2.70");
    assertThat(sold.status()).isEqualTo(OrderStatus.FILLED);
    ExecutionDto sell = sold.executions().get(0);
    assertThat(sell.price()).isEqualByComparingTo("2.60");                      // at the bid
    assertThat(sell.principal()).isEqualByComparingTo("520.00");
    assertThat(sell.netAmount()).isEqualByComparingTo("520.00");
    assertThat(sell.realizedPnl()).isEqualByComparingTo("20.00");
    assertThat(sell.settleDate()).isEqualTo(LocalDate.of(2026, 10, 13));        // T+1 (Monday is Columbus Day)
    assertThat(position(p, occ)).isNull();
    assertThat(account(p).cash().total()).isEqualByComparingTo("10020.00");
  }

  @Test
  void aLimitBuyRestsUntilTheAskReachesItAndFillsAtTheLimit() {
    Player p = human("10000.00");
    String occ = contract("P", "95");
    quote(occ, "1.10", 10, "1.20", 10);
    OrderDto o = place(p, limit(cid(), occ, Side.BUY, "3", "1.05", TimeInForce.DAY, false)).order();
    assertThat(o.reservedCash()).isEqualByComparingTo("315.00");                // 3 × 1.05 × 100
    after(Duration.ofSeconds(1));
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.WORKING);
    time.advance(Duration.ofSeconds(2));
    quote(occ, "0.95", 10, "1.05", 10);
    step();
    OrderDto filled = order(p, o.orderId());
    assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(filled.executions().get(0).principal()).isEqualByComparingTo("315.00");
  }

  @Test
  void anAbsurdSpreadIsNoQuoteToFillAgainst() {
    Player p = human("10000.00");
    String occ = contract("C", "110");
    quote(occ, "1.00", 10, "3.00", 10);                                         // $2 wide, 100% of the mid
    OrderDto o = place(p, market(cid(), occ, Side.BUY, "1")).order();
    after(Duration.ofSeconds(1));
    OrderDto waiting = order(p, o.orderId());
    assertThat(waiting.status()).isIn(OrderStatus.PENDING, OrderStatus.WORKING);
    assertThat(waiting.waitReason()).isEqualTo(WaitReason.AWAITING_QUOTE);
    time.advance(Duration.ofSeconds(1));
    quote(occ, "1.95", 10, "2.05", 10);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void optionOrdersAreWholeContractMarketOrLimitDayOrdersInTheRegularSession() {
    Player p = human("10000.00");
    String occ = contract("C", "120");
    quote(occ, "1.00", 10, "1.05", 10);
    rejected(() -> place(p, notional(cid(), occ, Side.BUY, "100.00")), ErrorCode.NOT_FRACTIONABLE);
    rejected(() -> place(p, market(cid(), occ, Side.BUY, "1.5")), ErrorCode.INVALID_QTY);
    rejected(() -> place(p, market(cid(), occ, Side.BUY, "1001")), ErrorCode.INVALID_QTY);
    rejected(() -> place(p, limit(cid(), occ, Side.BUY, "1", "1.00", TimeInForce.GTC, false)),
        ErrorCode.TIF_NOT_ALLOWED);
    rejected(() -> place(p, limit(cid(), occ, Side.BUY, "1", "1.005", TimeInForce.DAY, false)),
        ErrorCode.INVALID_TICK);
    rejected(() -> place(p, stop(cid(), occ, Side.SELL, "1", "0.50", TimeInForce.DAY)),
        ErrorCode.VALIDATION_FAILED);
    rejected(() -> place(p, limit(cid(), occ, Side.BUY, "1", "1.00", TimeInForce.DAY, true)),
        ErrorCode.SESSION_NOT_ALLOWED);
    rejected(() -> place(p, market(cid(), occ, Side.SELL, "1")), ErrorCode.SHORT_SELLING_NOT_ALLOWED);

    time.set(et("2026-10-09T17:00:00"));                                        // after the close
    quote(occ, "1.00", 10, "1.05", 10);
    rejected(() -> place(p, limit(cid(), occ, Side.BUY, "1", "1.00", TimeInForce.DAY, false)),
        ErrorCode.SESSION_NOT_ALLOWED);
  }

  @Test
  void turningOptionsOffRefusesBuysButNotSells() {
    Player p = human("10000.00");
    String occ = contract("P", "90");
    quote(occ, "0.50", 10, "0.55", 10);
    assertThat(fill(p, market(cid(), occ, Side.BUY, "1"), occ, "0.50", "0.55").status())
        .isEqualTo(OrderStatus.FILLED);
    admin = human().id();
    settings.put("options.enabled", "false", admin);
    rejected(() -> place(p, market(cid(), occ, Side.BUY, "1")), ErrorCode.SYMBOL_NOT_TRADABLE);
    assertThat(place(p, market(cid(), occ, Side.SELL, "1")).order().status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void anInTheMoneyCallCashSettlesAtIntrinsicValueOnceAndAnOutOfTheMoneyPutExpiresWorthless() {
    Player p = human("10000.00");
    String call = contract("C", "100");
    String put = contract("P", "100");
    quote(call, "2.40", 10, "2.50", 10);
    quote(put, "1.40", 10, "1.50", 10);
    assertThat(fill(p, market(cid(), call, Side.BUY, "3"), call, "2.40", "2.50").status())
        .isEqualTo(OrderStatus.FILLED);                                         // cost 750.00
    assertThat(fill(p, market(cid(), put, Side.BUY, "2"), put, "1.40", "1.50").status())
        .isEqualTo(OrderStatus.FILLED);                                         // cost 300.00
    // a resting sell that is still open at expiration is cancelled with OPTION_EXPIRED
    OrderDto resting = place(p, limit(cid(), call, Side.SELL, "1", "9.00", TimeInForce.DAY, false)).order();
    assertThat(account(p).cash().total()).isEqualByComparingTo("8950.00");

    time.set(et("2026-10-16T16:30:00"));
    var c = engine.<OptionExpiryHandler.Result>run(
        new EngineCommand.SettleOptionExpiryCmd(call, new BigDecimal("104.25")));
    assertThat(c.intrinsic()).isEqualByComparingTo("4.25");
    assertThat(c.accounts()).isEqualTo(1);
    assertThat(c.cash()).isEqualByComparingTo("1275.00");                       // 4.25 × 100 × 3
    var u = engine.<OptionExpiryHandler.Result>run(
        new EngineCommand.SettleOptionExpiryCmd(put, new BigDecimal("104.25")));
    assertThat(u.intrinsic()).isEqualByComparingTo("0");
    assertThat(u.cash()).isEqualByComparingTo("0.00");

    assertThat(position(p, call)).isNull();
    assertThat(position(p, put)).isNull();
    assertThat(account(p).cash().total()).isEqualByComparingTo("10225.00");     // 8950 + 1275
    assertThat(order(p, resting.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order(p, resting.orderId()).statusReason()).isEqualTo("OPTION_EXPIRED");

    OrderDto settled = expiryOrder(p, call);
    assertThat(settled.channel()).isEqualTo(Channel.SYSTEM);
    assertThat(settled.type()).isEqualTo(OrderType.MARKET);
    assertThat(settled.status()).isEqualTo(OrderStatus.FILLED);
    ExecutionDto x = settled.executions().get(0);
    assertThat(x.side()).isEqualTo(Side.SELL);
    assertThat(x.liquidity()).isEqualTo(Liquidity.EXPIRY);
    assertThat(x.price()).isEqualByComparingTo("4.25");
    assertThat(x.realizedPnl()).isEqualByComparingTo("525.00");                 // 1275 − 750
    assertThat(x.settleDate()).isEqualTo(LocalDate.of(2026, 10, 19));           // T+1
    ExecutionDto worthless = expiryOrder(p, put).executions().get(0);
    assertThat(worthless.price()).isEqualByComparingTo("0");
    assertThat(worthless.netAmount()).isEqualByComparingTo("0.00");
    assertThat(worthless.realizedPnl()).isEqualByComparingTo("-300.00");

    // a second settlement (a re-run, a catch-up) changes nothing
    var again = engine.<OptionExpiryHandler.Result>run(
        new EngineCommand.SettleOptionExpiryCmd(call, new BigDecimal("104.25")));
    assertThat(again.accounts()).isZero();
    assertThat(account(p).cash().total()).isEqualByComparingTo("10225.00");
  }

  @Test
  void theExpiryJobSettlesFromTheUnderlyingsOfficialCloseAndWaitsWithoutOne() {
    Player p = human("10000.00");
    String call = contract("C", "50");
    String underlying = call.substring(0, call.length() - 15);
    quote(call, "1.00", 10, "1.10", 10);
    assertThat(fill(p, market(cid(), call, Side.BUY, "1"), call, "1.00", "1.10").status())
        .isEqualTo(OrderStatus.FILLED);

    time.set(et("2026-10-16T16:30:00"));
    expiryJob.settleThrough(EXPIRATION);                                        // no official close yet: waits
    assertThat(position(p, call)).isNotNull();

    jdbc.sql("""
            INSERT INTO daily_mark (symbol, trade_date, official_close, quality, updated_at)
            VALUES (?, ?, 51.75, 'FINAL', ?)""").params(underlying, EXPIRATION, Jdbc.ts(time.now())).update();
    expiryJob.settleThrough(EXPIRATION);
    assertThat(position(p, call)).isNull();
    assertThat(expiryOrder(p, call).executions().get(0).principal()).isEqualByComparingTo("175.00");
  }

  @Test
  void anExpiredContractCannotBeBought() {
    Player p = human("10000.00");
    String occ = contract("C", "105");
    time.set(et("2026-10-19T10:00:00"));
    quote(occ, "0.05", 10, "0.10", 10);
    rejected(() -> place(p, market(cid(), occ, Side.BUY, "1")), ErrorCode.SYMBOL_NOT_TRADABLE);
  }

  private OrderDto expiryOrder(Player p, String symbol) {
    String id = jdbc.sql("SELECT public_id FROM trade_order WHERE account_id = ? AND client_order_id = ?")
        .params(p.accountId(), "expiry:" + symbol).query(String.class).single();
    return order(p, id);
  }
}
