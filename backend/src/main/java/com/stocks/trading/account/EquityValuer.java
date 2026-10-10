package com.stocks.trading.account;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Live equity of an account (§16.1), for unit accounting at a flow (§16.2), a reset's final equity and the Account
 * page. It asks WS4's {@link ValuationService} (which reads through the caller's transaction). While that contract is
 * a Day-0 stub, it values the account itself with the same definition: cash + Σ {@code MARKET_VALUE(qty × mark)} +
 * ACCRUED dividend receivables, marks from {@code MarketDataService.mark}, quality = the worst mark used.
 */
@Component
public class EquityValuer {

  private final ValuationService valuations;
  private final AccountStore accounts;
  private final MarketDataService marketData;
  private final TimeSource time;

  public EquityValuer(ValuationService valuations, AccountStore accounts, MarketDataService marketData,
                      TimeSource time) {
    this.valuations = valuations;
    this.accounts = accounts;
    this.marketData = marketData;
    this.time = time;
  }

  public Valuation value(AccountRow acc) {
    try {
      return valuations.valueNow(acc.id());
    } catch (ApiException e) {
      if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
        throw e;
      }
      return local(acc);
    }
  }

  /** The §16.1 valuation computed here (used until WS4's ValuationService lands). */
  Valuation local(AccountRow acc) {
    BigDecimal lmv = Money.ZERO;
    DataQuality worst = DataQuality.REALTIME_CONSOLIDATED;
    Map<String, Mark> marks = new LinkedHashMap<>();
    for (PositionRow p : accounts.positions(acc.id())) {
      Mark m = marketData.mark(p.symbol());
      marks.put(p.symbol(), m);
      if (m.price() != null) {
        lmv = lmv.add(Rounding.MARKET_VALUE.multiply(p.qty(), m.price()));
      }
      worst = worse(worst, m.price() == null ? DataQuality.NONE : m.quality());
    }
    BigDecimal receivable = accounts.dividendsReceivable(acc.id());
    BigDecimal equity = acc.cashBalance().add(lmv).add(receivable);
    BigDecimal u = unitValue(acc, equity);
    return new Valuation(acc.id(), time.now(), acc.cashBalance(), lmv, receivable, equity, acc.netContributions(),
        acc.units(), u, acc.unitSeries(), worst, marks);
  }

  /** {@code U = UNIT(E / units)} if units &gt; 0, else the last transfer's U (§16.2), else 1. */
  public BigDecimal unitValue(AccountRow acc, BigDecimal equity) {
    if (acc.units().signum() > 0) {
      return Rounding.UNIT.divide(equity, acc.units());
    }
    return accounts.lastTransferUnitValue(acc.id()).orElse(BigDecimal.ONE.setScale(18));
  }

  /** The worse of two qualities: SIMULATED and the realtime ones are fine; then DELAYED, SYNTHETIC, STALE, NONE. */
  public static DataQuality worse(DataQuality a, DataQuality b) {
    return rank(b) > rank(a) ? b : a;
  }

  private static int rank(DataQuality q) {
    return switch (q) {
      case REALTIME_CONSOLIDATED -> 0;
      case SIMULATED -> 1;
      case REALTIME_SINGLE_VENUE -> 2;
      case DELAYED_15M -> 3;
      case SYNTHETIC -> 4;
      case STALE -> 5;
      case NONE -> 6;
    };
  }
}
