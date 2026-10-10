package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the rounding leak found in review: execution principal used to round HALF_UP for both sides,
 * so a buyer could pay less than the exact value of the shares (down to $0.00) and a round trip at one unchanged price
 * created cash. Principal now rounds against the player (§6.2: CEILING for a BUY, FLOOR for a SELL), and the $1.00 buy
 * minimum of §11.1 also binds fractional quantities (check 14).
 */
class PrincipalRoundingTest extends TradingTestBase {

  private OrderDto fill(Player p, PlaceOrderRequest req, String s, String bid, String ask) {
    OrderDto o = place(p, req).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, bid, 1000, ask, 1000);
    step();
    return order(p, o.orderId());
  }

  @Test
  void aFractionalBuyWorthLessThanTheBuyMinimumIsRejected() {
    Player p = human();
    String s = symbol();
    quote(s, "498.99", 1000, "499.00", 1000);
    String cid = cid();
    assertThatThrownBy(() -> place(p, market(cid, s, Side.BUY, "0.00001")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.NOTIONAL_TOO_SMALL);
          assertThat(e.fields()).anySatisfy(f -> assertThat(f.field()).isEqualTo("qty"));
        });
    assertThat(orderQueries.byClientOrderId(p.accountId(), cid, false)).isEmpty();
    // 0.002 × 499.00 = 0.998 is still under $1.00; 0.00201 (1.00299) is enough
    assertThatThrownBy(() -> place(p, market(cid(), s, Side.BUY, "0.002")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOTIONAL_TOO_SMALL));
    assertThat(place(p, market(cid, s, Side.BUY, "0.00201")).order().status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void aBuyPaysAtLeastTheExactValueAndASellReceivesAtMostIt() {
    Player p = human();
    String s = symbol();
    quote(s, "499.00", 1000, "499.00", 1000);
    // 0.00201 × 499.00 = 1.00299: the buyer pays 1.01 (was 1.00 under HALF_UP)
    OrderDto b = fill(p, market(cid(), s, Side.BUY, "0.00201"), s, "499.00", "499.00");
    assertThat(b.status()).isEqualTo(OrderStatus.FILLED);
    ExecutionDto bx = b.executions().get(0);
    assertThat(bx.principal()).isEqualByComparingTo("1.01");
    assertThat(bx.netAmount()).isEqualByComparingTo("-1.01");
    // 0.00201 × 499.00 = 1.00299 on the way out: the seller receives 1.00 before fees
    OrderDto x = fill(p, market(cid(), s, Side.SELL, "0.00201"), s, "499.00", "499.00");
    assertThat(x.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(x.executions().get(0).principal()).isEqualByComparingTo("1.00");
  }

  @Test
  void roundTripsAtOneUnchangedPriceNeverCreateCash() {
    Player p = human();
    String s = symbol();
    quote(s, "499.00", 1000, "499.00", 1000);
    for (int i = 0; i < 10; i++) {
      assertThat(fill(p, market(cid(), s, Side.BUY, "0.00201"), s, "499.00", "499.00").status())
          .isEqualTo(OrderStatus.FILLED);
    }
    OrderDto sell = fill(p, market(cid(), s, Side.SELL, "0.0201"), s, "499.00", "499.00");
    assertThat(sell.status()).isEqualTo(OrderStatus.FILLED);
    Balances b = accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE);
    // paid 10 × 1.01; received FLOOR(10.0299) − SEC 0.01 = 10.01
    assertThat(b.cashTotal()).isEqualByComparingTo("499.91");
    assertThat(b.cashTotal()).isLessThanOrEqualTo(new BigDecimal("500.00"));
  }

  @Test
  void aSubDollarWholeShareBuyRoundsTheBuyersPrincipalUp() {
    Player p = human();
    String s = symbol();
    quote(s, "0.5048", 1000, "0.5049", 1000);
    OrderDto f = fill(p, limit(cid(), s, Side.BUY, "1", "0.5049", TimeInForce.DAY, false), s, "0.5048", "0.5049");
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions().get(0).principal()).isEqualByComparingTo("0.51");
  }

  @Test
  void theSchemaRejectsAZeroPrincipalBuy() {
    Player p = human();
    String s = symbol();
    quote(s, "10.00", 1000, "10.00", 1000);
    OrderDto f = fill(p, market(cid(), s, Side.BUY, "1"), s, "10.00", "10.00");
    long execId = jdbc.sql("SELECT id FROM execution WHERE public_id = ?")
        .param(f.executions().get(0).executionId()).query(Long.class).single();
    assertThatThrownBy(() -> jdbc.sql("""
            INSERT INTO execution (public_id, order_id, account_id, symbol, side, qty, price, principal, net_amount,
                                   liquidity, executed_at, trade_date, settle_date, ref_source, ref_quality)
            SELECT 'exe_zero_principal', order_id, account_id, symbol, 'BUY', 0.00001, 499.0000, 0.00, 0.00,
                   liquidity, executed_at, trade_date, settle_date, ref_source, ref_quality
              FROM execution WHERE id = ?""").param(execId).update())
        .hasMessageContaining("ck_execution_buy_principal");
  }
}
