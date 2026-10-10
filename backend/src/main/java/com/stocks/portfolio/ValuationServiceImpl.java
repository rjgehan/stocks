package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.portfolio.UnitMath;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.PositionRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Live valuation (§16.1): {@code E = cash + Σ MARKET_VALUE(qty × mark) + receivable}, at {@code now}. Cash is
 * {@code account.cash_balance} (the ledger cache, invariant I2), the receivable is point-in-time, marks come from
 * {@link MarkResolver} and the worst quality among them is reported. Holds do not reduce equity.
 *
 * <p>U = {@code UNIT(E / units)} while the account has units, else the U of its last applied transfer (§16.2). Reads
 * go through the caller's transaction ({@code JdbcClient} is transaction-aware), so the engine values an account
 * mid-command, e.g. E⁻ for a deposit (§21.2).
 */
@Service
public class ValuationServiceImpl implements ValuationService {

  private final PortfolioRepository repository;
  private final MarkResolver marks;
  private final TimeSource time;

  public ValuationServiceImpl(PortfolioRepository repository, MarkResolver marks, TimeSource time) {
    this.repository = repository;
    this.marks = marks;
    this.time = time;
  }

  @Override
  public Valuation valueNow(long accountId) {
    AccountRow account = repository.account(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + accountId + " does not exist."));
    Instant now = time.now();
    List<PositionRow> positions = repository.positions(accountId);
    Map<String, Mark> markMap = new LinkedHashMap<>();
    for (PositionRow p : positions) {
      markMap.put(p.symbol(), marks.live(p.symbol(), p.qty(), p.costBasis()));
    }
    return value(account, positions, markMap, repository.receivableAt(accountId, now), now);
  }

  @Override
  public Map<Long, Valuation> valueAllActive() {
    Instant now = time.now();
    List<AccountRow> accounts = repository.activeAccounts();
    Map<Long, List<PositionRow>> positions = repository.positionsOfActiveAccounts();
    Map<Long, BigDecimal> receivables = repository.receivablesOfActiveAccounts(now);
    Map<String, Mark> markCache = new HashMap<>();
    Map<Long, Valuation> out = new LinkedHashMap<>();
    for (AccountRow a : accounts) {
      List<PositionRow> held = positions.getOrDefault(a.id(), List.of());
      Map<String, Mark> markMap = new LinkedHashMap<>();
      for (PositionRow p : held) {
        Mark m = markCache.get(p.symbol());
        if (m == null || m.quality() == DataQuality.NONE) {
          // NONE marks fall back to this position's own cost, so they are not shared between accounts
          m = marks.live(p.symbol(), p.qty(), p.costBasis());
          markCache.put(p.symbol(), m);
        }
        markMap.put(p.symbol(), m);
      }
      out.put(a.id(), value(a, held, markMap, receivables.getOrDefault(a.id(), Money.ZERO), now));
    }
    return Collections.unmodifiableMap(out);
  }

  private Valuation value(AccountRow a, List<PositionRow> positions, Map<String, Mark> markMap,
                          BigDecimal receivable, Instant now) {
    BigDecimal longMarketValue = Money.ZERO;
    List<DataQuality> qualities = new ArrayList<>();
    for (PositionRow p : positions) {
      Mark m = markMap.get(p.symbol());
      longMarketValue = longMarketValue.add(Rounding.MARKET_VALUE.multiply(p.qty(), m.price()));
      qualities.add(m.quality());
    }
    BigDecimal cash = Money.cents(a.cash());
    BigDecimal equity = cash.add(longMarketValue).add(receivable);
    BigDecimal units = Rounding.UNIT.apply(a.units());
    BigDecimal unitValue = units.signum() > 0 ? UnitMath.unitValue(equity, units)
        : UnitMath.unitValueOrLast(equity, units, repository.lastUnitValue(a.id()).orElse(null));
    return new Valuation(a.id(), now, cash, longMarketValue, receivable, equity, Money.cents(a.netContributions()),
        units, unitValue, a.unitSeries(), Qualities.worst(qualities), Collections.unmodifiableMap(markMap));
  }
}
