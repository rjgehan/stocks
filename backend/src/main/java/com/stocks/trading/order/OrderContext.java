package com.stocks.trading.order;

import com.stocks.common.money.Qty;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.Balances;
import com.stocks.trading.account.BalanceCalculator;
import com.stocks.trading.order.OrderValidator.AccountSnapshot;
import com.stocks.trading.order.OrderValidator.MarketSnapshot;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderStore;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds the account and market snapshots {@link OrderValidator} checks against. Reads through the caller's
 * transaction: committed state for a preview, the engine's own state inside {@code PlaceOrderCmd}.
 */
@Component
public class OrderContext {

  private final AccountStore accounts;
  private final OrderStore orders;
  private final MarketDataService marketData;
  private final JdbcClient jdbc;

  public OrderContext(AccountStore accounts, OrderStore orders, MarketDataService marketData, JdbcClient jdbc) {
    this.accounts = accounts;
    this.orders = orders;
    this.marketData = marketData;
    this.jdbc = jdbc;
  }

  /**
   * The account as the checks see it. {@code excludeOrderId}: an order being replaced (its sell reservation does not
   * count), or -1.
   */
  public AccountSnapshot account(AccountRow acc, String symbol, LocalDate asOfEt, long excludeOrderId) {
    Balances b = balances(acc, asOfEt);
    BigDecimal position = accounts.position(acc.id(), symbol).map(PositionRow::qty).orElse(null);
    BigDecimal reserved = symbol == null ? Qty.ZERO : orders.reservedSellQty(acc.id(), symbol, excludeOrderId);
    int open = orders.countOpen(acc.id()) - (excludeOrderId > 0 ? 1 : 0);
    return new AccountSnapshot(acc.id(), acc.restriction(), b, open, position, reserved);
  }

  /** §10.2 balances of an account as of an ET date. */
  public Balances balances(AccountRow acc, LocalDate asOfEt) {
    return BalanceCalculator.compute(asOfEt, accounts.ledgerSums(acc.id(), asOfEt), orders.openBuyHolds(acc.id()),
        acc.restriction());
  }

  public MarketSnapshot market(String symbol, LocalDate todayEt) {
    Asset asset = symbol == null ? null : marketData.asset(symbol).orElse(null);
    QuoteView quote = symbol == null ? null : marketData.quote(symbol);
    return new MarketSnapshot(asset, symbol != null && corporateActionHold(symbol, todayEt), quote,
        reference(symbol, quote));
  }

  /**
   * The reference price of checks 19–20 and the queued hold (§10.3): the last trade, else the previous close
   * (split-adjusted by WS2), else null.
   */
  BigDecimal reference(String symbol, QuoteView q) {
    if (q != null && q.last() != null && q.last().signum() > 0) {
      return q.last();
    }
    if (symbol == null) {
      return null;
    }
    Optional<BigDecimal> prev = marketData.previousClose(symbol);
    if (prev.isPresent() && prev.get().signum() > 0) {
      return prev.get();
    }
    return q != null && q.prevClose() != null && q.prevClose().signum() > 0 ? q.prevClose() : null;
  }

  /** A NEEDS_ADMIN split with {@code ex_date ≤ today} puts the symbol on hold (§14). */
  boolean corporateActionHold(String symbol, LocalDate todayEt) {
    return jdbc.sql("""
            SELECT COUNT(*) FROM corporate_action
             WHERE symbol = ? AND status = 'NEEDS_ADMIN' AND action_type IN ('FORWARD_SPLIT','REVERSE_SPLIT')
               AND ex_date <= ?""")
        .params(symbol, todayEt).query(Integer.class).single() > 0;
  }
}
