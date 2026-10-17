package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.WarningCode;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.trading.testkit.TradingTestBase;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Crypto pairs on the engine (§29): orders work and fill on a Saturday while stocks queue for Monday's open, fills
 * settle on the trade date with no SEC/TAF/CAT fees and never cause a good-faith violation, fractional quantities work
 * on every order type and time in force, and {@code crypto.enabled=false} refuses buys but not sells.
 */
class CryptoScenarioTest extends TradingTestBase {

  static final String BTC = "BTC/USD";
  static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);

  @Autowired SettingsService settings;

  @BeforeEach
  void saturday() {
    md.withAsset(new Asset(BTC, "Bitcoin", "CRYPTO", AssetType.CRYPTO, true, true, false, false, false,
        LiquidityTier.MEGA, true));
    time.set(et("2026-10-10T11:00:00"));
  }

  @AfterEach
  void cryptoBackOn() {
    if (admin != null) {
      settings.put("crypto.enabled", "true", admin.id());
    }
  }

  private Player admin;

  @Test
  void aSaturdayCryptoBuyFillsSettlesTheSameDayWithoutFeesAndSellsWithoutAViolation() {
    Player p = human();
    quote(BTC, "112000.00", 1, "112010.00", 1);

    PlaceOrderResponse r = place(p, market(cid(), BTC, Side.BUY, "0.004"));
    OrderDto o = r.order();
    assertThat(o.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(o.waitReason()).isEqualTo(WaitReason.LATENCY);           // not queued for Monday's open
    assertThat(o.sessionDate()).isEqualTo(SATURDAY);
    assertThat(o.expiresAt()).isEqualTo(et("2026-10-11T00:00:00"));     // DAY = until 24:00 ET
    assertThat(o.fractional()).isTrue();

    time.advance(Duration.ofSeconds(1));
    quote(BTC, "112000.00", 1, "112010.00", 1);
    step();
    OrderDto filled = order(p, o.orderId());
    assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(filled.filledQty()).isEqualByComparingTo("0.004");      // one fill for the whole fraction
    ExecutionDto buy = filled.executions().get(0);
    assertThat(buy.price()).isEqualByComparingTo("112010.00");
    assertThat(buy.principal()).isEqualByComparingTo("448.04");
    assertThat(buy.netAmount()).isEqualByComparingTo("-448.04");        // no CAT fee
    assertThat(buy.tradeDate()).isEqualTo(SATURDAY);
    assertThat(buy.settleDate()).isEqualTo(SATURDAY);                   // settles at once

    Balances b = accountQueries.balances(p.accountId(), SATURDAY);
    assertThat(b.cashTotal()).isEqualByComparingTo("51.96");
    assertThat(b.settledCash()).isEqualByComparingTo("51.96");
    assertThat(b.unsettledCash()).isEqualByComparingTo("0");

    // selling right away is no good-faith violation: crypto never carries unsettled funding
    OrderDto sell = place(p, market(cid(), BTC, Side.SELL, "0.004")).order();
    time.advance(Duration.ofSeconds(1));
    quote(BTC, "112100.00", 1, "112110.00", 1);
    step();
    OrderDto sold = order(p, sell.orderId());
    assertThat(sold.status()).isEqualTo(OrderStatus.FILLED);
    ExecutionDto x = sold.executions().get(0);
    assertThat(x.principal()).isEqualByComparingTo("448.40");
    assertThat(x.netAmount()).isEqualByComparingTo("448.40");           // no SEC fee
    assertThat(x.realizedPnl()).isEqualByComparingTo("0.36");
    assertThat(x.settleDate()).isEqualTo(SATURDAY);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM account_violation WHERE account_id = ?").param(p.accountId())
        .query(Long.class).single()).isZero();
    assertThat(accountQueries.balances(p.accountId(), SATURDAY).settledCash()).isEqualByComparingTo("500.36");
  }

  @Test
  void stocksStillQueueForMondaysOpenOnASaturday() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    print(s, "100.00", 100);                                           // the queued hold needs a reference price
    OrderDto o = place(p, market(cid(), s, Side.BUY, "1")).order();
    assertThat(o.waitReason()).isEqualTo(WaitReason.QUEUED_FOR_OPEN);
    assertThat(o.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 12));
  }

  @Test
  void fractionalCryptoQuantitiesWorkOnLimitGtcOrdersWithoutAPauseWarning() {
    Player p = human("100000.00");
    quote(BTC, "112000.00", 1, "112010.00", 1);
    PlaceOrderResponse r = place(p, limit(cid(), BTC, Side.BUY, "0.12345", "111000.00", TimeInForce.GTC, false));
    assertThat(r.order().status()).isEqualTo(OrderStatus.PENDING);
    assertThat(r.order().qty()).isEqualByComparingTo("0.12345");
    assertThat(r.warnings()).extracting(w -> w.code()).doesNotContain(WarningCode.WILL_PAUSE_OUTSIDE_SESSION.name(),
        WarningCode.QUEUED_FOR_OPEN.name());
    assertThat(r.order().reservedCash()).isEqualByComparingTo("13702.95");   // 0.12345 × 111000, no fees
  }

  @Test
  void turningCryptoOffRefusesBuysButNotSells() {
    Player p = human();
    quote(BTC, "112000.00", 1, "112010.00", 1);
    OrderDto o = place(p, market(cid(), BTC, Side.BUY, "0.001")).order();
    time.advance(Duration.ofSeconds(1));
    quote(BTC, "112000.00", 1, "112010.00", 1);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);

    admin = human();
    settings.put("crypto.enabled", "false", admin.id());
    assertThatThrownBy(() -> place(p, market(cid(), BTC, Side.BUY, "0.001")))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.SYMBOL_NOT_TRADABLE));
    assertThat(place(p, market(cid(), BTC, Side.SELL, "0.001")).order().status()).isEqualTo(OrderStatus.PENDING);
  }
}
