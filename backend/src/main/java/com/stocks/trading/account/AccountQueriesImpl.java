package com.stocks.trading.account;

import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.ViolationType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.ViolationDto;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderStore;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Account reads (§18.2): the live {@code Account} (§18.3, run-relative totals per §16.3), balances (§10.2),
 * positions, merged activities, violations and runs. Read-only, committed state plus live marks; day P&L comes
 * from WS4's {@code PerformanceQueries} (the same anchors as the leaderboard).
 */
@Service
public class AccountQueriesImpl implements AccountQueries {

  private final AccountStore accounts;
  private final OrderStore orders;
  private final EquityValuer valuer;
  private final MarketDataService marketData;
  private final PerformanceQueries performance;
  private final DayPnl dayPnl;
  private final ActivityQueries activities;
  private final RulesProperties rules;
  private final TimeSource time;
  private final JdbcClient jdbc;

  public AccountQueriesImpl(AccountStore accounts, OrderStore orders, EquityValuer valuer,
                            MarketDataService marketData, PerformanceQueries performance, DayPnl dayPnl,
                            ActivityQueries activities, RulesProperties rules, TimeSource time, JdbcClient jdbc) {
    this.accounts = accounts;
    this.orders = orders;
    this.valuer = valuer;
    this.marketData = marketData;
    this.performance = performance;
    this.dayPnl = dayPnl;
    this.activities = activities;
    this.rules = rules;
    this.time = time;
    this.jdbc = jdbc;
  }

  @Override
  public long activeAccountId(long playerId) {
    return accounts.activeByPlayer(playerId).map(AccountRow::id)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "The player has no active account."));
  }

  @Override
  public AccountDto account(long accountId) {
    AccountRow acc = row(accountId);
    Instant now = time.now();
    Balances b = balances(accountId, Et.date(now));
    Valuation v = valuer.value(acc);
    BigDecimal equity = v.equity().setScale(Money.SCALE, RoundingMode.HALF_EVEN);
    BigDecimal u = valuer.unitValue(acc, equity);
    BigDecimal uOpen = accounts.openingUnitValue(accountId).orElse(BigDecimal.ONE);
    BigDecimal totalPnl = equity.subtract(acc.netContributions());
    BigDecimal totalReturn = DayPnl.ratio(u, uOpen);
    DayPnl.Account day = accountDayPnl(acc);
    int active = accounts.activeViolations(accountId, now);
    AccountDto.AccountGfvDto gfv = new AccountDto.AccountGfvDto(active,
        accounts.nextViolationExpiry(accountId, now).orElse(null), rules.gfv().settledCashOnlyAt(),
        rules.gfv().liquidationOnlyAt());
    return new AccountDto(acc.publicId(), acc.runNo(), AccountType.CASH, acc.status(), acc.restriction(),
        acc.restrictionUntil(), acc.restrictionReason(), equity, v.longMarketValue(), v.dividendsReceivable(),
        new AccountDto.AccountCashDto(b.cashTotal(), b.settledCash(), b.unsettledCash(), b.settledAvailable(),
            b.withdrawable()),
        b.openBuyHolds(), b.buyingPower(), acc.startingCash(), acc.netContributions(), totalPnl, totalReturn, u,
        day.dayPnl(), day.dayReturn(), gfv, dayTrades(accountId, now), acc.openedAt(),
        v.marks().isEmpty() ? DataQuality.REALTIME_CONSOLIDATED : v.marksQuality(), now);
  }

  private DayPnl.Account accountDayPnl(AccountRow acc) {
    AccountPnl p = performance.accountPnl(acc.id());
    return new DayPnl.Account(p.dayPnl(), p.dayReturn());
  }

  @Override
  public Balances balances(long accountId, LocalDate asOfEt) {
    AccountRow acc = row(accountId);
    return BalanceCalculator.compute(asOfEt, accounts.ledgerSums(accountId, asOfEt), orders.openBuyHolds(accountId),
        acc.restriction());
  }

  @Override
  public List<PositionDto> positions(long accountId) {
    AccountRow acc = row(accountId);
    List<PositionRow> held = accounts.positions(accountId);
    Map<String, Mark> marks = new HashMap<>();
    Map<String, BigDecimal> prices = new HashMap<>();
    BigDecimal lmv = Money.ZERO;
    for (PositionRow p : held) {
      Mark m = marketData.mark(p.symbol());
      marks.put(p.symbol(), m);
      BigDecimal price = m.price() != null ? m.price() : avgCost(p);
      prices.put(p.symbol(), price);
      lmv = lmv.add(Rounding.marketValue(p.symbol(), p.qty(), price));
    }
    BigDecimal equity = acc.cashBalance().add(lmv).add(accounts.dividendsReceivable(accountId));
    Map<String, PositionDayPnl> day = positionDayPnl(accountId);
    return held.stream().map(p -> {
      Mark m = marks.get(p.symbol());
      BigDecimal price = prices.get(p.symbol());
      BigDecimal mv = Rounding.marketValue(p.symbol(), p.qty(), price);
      BigDecimal upnl = mv.subtract(p.costBasis());
      BigDecimal uret = p.costBasis().signum() > 0 ? upnl.divide(p.costBasis(), 10, RoundingMode.HALF_EVEN)
          : BigDecimal.ZERO.setScale(10);
      BigDecimal available = p.qty().subtract(orders.reservedSellQty(accountId, p.symbol(), -1)).max(Qty.ZERO);
      Optional<Asset> asset = safeAsset(p.symbol());
      QuoteView q = safeQuote(p.symbol());
      PositionDayPnl d = day.get(p.symbol());
      return new PositionDto(p.symbol(), asset.map(Asset::name).orElse(p.symbol()), OrderValidator.qtyOut(p.qty()),
          OrderValidator.qtyOut(available), avgCost(p), p.costBasis(), q == null ? null : q.last(),
          price, m.price() == null ? DataQuality.NONE : m.quality(), mv, upnl, uret,
          d == null ? Money.ZERO : d.dayPnl(), d == null ? null : d.dayReturn(),
          equity.signum() > 0 ? mv.divide(equity, 10, RoundingMode.HALF_EVEN) : BigDecimal.ZERO.setScale(10),
          asset.map(Asset::fractionable).orElse(false), p.openedAt());
    }).toList();
  }

  private Map<String, PositionDayPnl> positionDayPnl(long accountId) {
    return performance.positionDayPnl(accountId);
  }

  /** Cost per share: an option's cost basis covers 100 shares per contract (§30). */
  private static BigDecimal avgCost(PositionRow p) {
    return p.qty().signum() == 0 ? BigDecimal.ZERO.setScale(6)
        : p.costBasis().divide(p.qty().multiply(OptionSymbol.multiplier(p.symbol())), 6, RoundingMode.HALF_EVEN);
  }

  private Optional<Asset> safeAsset(String symbol) {
    try {
      return marketData.asset(symbol);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private QuoteView safeQuote(String symbol) {
    try {
      return marketData.quote(symbol);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public Page<ActivityDto> activities(long accountId, ActivityFilter filter, Cursor cursor, int limit) {
    row(accountId);
    return activities.page(accountId, filter, cursor, limit);
  }

  @Override
  public List<ViolationDto> violations(long accountId) {
    return jdbc.sql("""
            SELECT v.public_id, v.violation_type, v.symbol, o.public_id AS order_public_id, v.unsettled_amount,
                   v.funding_settle_date, v.trade_date, v.occurred_at, v.expires_at
              FROM account_violation v JOIN trade_order o ON o.id = v.order_id
             WHERE v.account_id = ? ORDER BY v.occurred_at DESC, v.id DESC""")
        .param(accountId)
        .query((rs, n) -> new ViolationDto(rs.getString("public_id"), ViolationType.valueOf(rs.getString(
            "violation_type")), rs.getString("symbol"), rs.getString("order_public_id"),
            rs.getBigDecimal("unsettled_amount"), Jdbc.date(rs, "funding_settle_date"), Jdbc.date(rs, "trade_date"),
            Jdbc.instant(rs, "occurred_at"), Jdbc.instant(rs, "expires_at")))
        .list();
  }

  @Override
  public List<RunDto> runs(long playerId) {
    return accounts.runs(playerId).stream().map(acc -> {
      BigDecimal uOpen = accounts.openingUnitValue(acc.id()).orElse(BigDecimal.ONE);
      BigDecimal equity;
      BigDecimal u;
      if (acc.status() == AccountStatus.CLOSED) {
        equity = acc.finalEquity() == null ? Money.ZERO : acc.finalEquity();
        u = acc.finalUnitValue();
      } else {
        equity = valuer.value(acc).equity().setScale(Money.SCALE, RoundingMode.HALF_EVEN);
        u = valuer.unitValue(acc, equity);
      }
      return new RunDto(acc.publicId(), acc.runNo(), acc.status(), acc.openedAt(), acc.closedAt(),
          acc.startingCash(), acc.finalEquity(), acc.netContributions(), DayPnl.ratio(u, uOpen),
          equity.subtract(acc.netContributions()));
    }).toList();
  }

  /** Round trips (a buy and a sell of one symbol on one trade date) in the last 5 trading days; display only. */
  int dayTrades(long accountId, Instant now) {
    LocalDate from = dayPnl.fiveTradingDaysStart(Et.date(now));
    return jdbc.sql("""
            SELECT COUNT(*) FROM (SELECT symbol, trade_date FROM execution WHERE account_id = ? AND trade_date >= ?
                                   GROUP BY symbol, trade_date
                                  HAVING COUNT(DISTINCT side) = 2) t""")
        .params(accountId, from).query(Integer.class).single();
  }

  private AccountRow row(long accountId) {
    return accounts.byId(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "No such account."));
  }
}
