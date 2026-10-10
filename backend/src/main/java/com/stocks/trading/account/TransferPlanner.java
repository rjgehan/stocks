package com.stocks.trading.account;

import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.Warning;
import com.stocks.common.error.WarningCode;
import com.stocks.common.money.Money;
import com.stocks.common.time.Et;
import com.stocks.common.web.Text;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarkBasis;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.portfolio.UnitMath;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderStore;
import com.stocks.trading.store.PlayerRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Steps 2–5 of §21.2, read-only: resolve the targets, value each account at the flow (E⁻, live marks), apply the
 * mark-quality gate and compute the unit accounting of §16.2 (normal, empty account, wipe-out rebase, PnL
 * correction) and the withdrawable check. {@code CashTransferService.preview} returns the plan; the engine's
 * {@code ApplyCashTransfersCmd} writes it.
 */
@Component
public class TransferPlanner {

  public static final String SKIP_INSUFFICIENT_WITHDRAWABLE = "INSUFFICIENT_WITHDRAWABLE";

  /** One target account and what the flow does to it. {@code amount} is signed (+ into the account). */
  public record Planned(PlayerRow player, AccountRow account, BigDecimal amount, boolean contribution,
                        Balances balances, Valuation valuation, BigDecimal unitValue, BigDecimal unitsDelta,
                        int unitSeriesAfter, boolean rebased, boolean wouldApply, String skipReason,
                        DataQuality marksQuality, String worstMarkBasis, List<Warning> warnings, boolean degraded,
                        List<String> degradedSymbols) {}

  /** The batch plan. */
  public record Plan(Instant asOf, SessionType session, List<Planned> accounts, BigDecimal spyMark) {
    public boolean degraded() {
      return accounts.stream().anyMatch(Planned::degraded);
    }
  }

  private final AccountStore accounts;
  private final OrderStore orders;
  private final EquityValuer valuer;
  private final MarketDataService marketData;
  private final BigDecimal lowEquityWarning;
  private final String benchmarkSymbol;

  public TransferPlanner(AccountStore accounts, OrderStore orders, EquityValuer valuer, MarketDataService marketData,
                         @Value("${stocks.performance.low-equity-warning:1.00}") BigDecimal lowEquityWarning,
                         @Value("${stocks.performance.benchmark-symbol:SPY}") String benchmarkSymbol) {
    this.accounts = accounts;
    this.orders = orders;
    this.valuer = valuer;
    this.marketData = marketData;
    this.lowEquityWarning = lowEquityWarning;
    this.benchmarkSymbol = benchmarkSymbol;
  }

  /** Validates the command's shape (§21.1 {@code CashTransferRequest}); 400 {@code VALIDATION_FAILED}. */
  public static CashTransferCommand validate(CashTransferCommand c) {
    if (c.type() == null || c.type() == TransferType.INITIAL) {
      throw ApiException.validation("type", "type must be DEPOSIT, WITHDRAWAL or ADJUSTMENT");
    }
    BigDecimal amount = Money.require("amount", c.amount());
    if (c.type() == TransferType.ADJUSTMENT ? amount.signum() == 0 : amount.signum() <= 0) {
      throw ApiException.validation("amount", c.type() == TransferType.ADJUSTMENT ? "amount must be non-zero"
          : "amount must be positive (the direction comes from the type)");
    }
    String memo = Text.trimToNull(Text.stripControl(c.memo()));
    if (memo == null || memo.length() > 200) {
      throw ApiException.validation("memo", "memo is required, 1–200 characters");
    }
    if (c.scope() == TransferScope.SINGLE && Text.isBlank(c.handleOrNull())) {
      throw ApiException.validation("target.handle", "handle is required for scope SINGLE");
    }
    if (c.scope() != TransferScope.SINGLE && c.scope() != TransferScope.ALL) {
      throw ApiException.validation("target.scope", "scope must be SINGLE or ALL");
    }
    Set<PlayerKind> kinds = c.kinds() == null || c.kinds().isEmpty() ? EnumSet.allOf(PlayerKind.class)
        : EnumSet.copyOf(c.kinds());
    AdjustmentTreatment treat = c.type() == TransferType.ADJUSTMENT && c.treatAs() != null ? c.treatAs()
        : AdjustmentTreatment.CONTRIBUTION;
    return new CashTransferCommand(c.scope(), c.handleOrNull() == null ? null : c.handleOrNull().trim()
        .toLowerCase(java.util.Locale.ROOT), kinds, c.type(), amount, treat, memo, c.allOrNothing(),
        c.acceptDegradedMarks());
  }

  /** The signed flow: + for deposits, − for withdrawals, as given for adjustments. */
  public static BigDecimal signed(CashTransferCommand c) {
    return c.type() == TransferType.WITHDRAWAL ? c.amount().negate() : c.amount();
  }

  public Plan plan(CashTransferCommand c, Instant now, SessionType session) {
    List<PlayerRow> targets = targets(c);
    BigDecimal flow = signed(c);
    boolean contribution = c.type() != TransferType.ADJUSTMENT || c.treatAs() == AdjustmentTreatment.CONTRIBUTION;
    List<Planned> out = new ArrayList<>();
    for (PlayerRow p : targets) {
      AccountRow acc = accounts.activeByPlayer(p.id()).orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND,
          p.handle() + " has no active account."));
      out.add(planOne(p, acc, flow, contribution, now, session));
    }
    Mark spy = safeMark(benchmarkSymbol);
    return new Plan(now, session, out, spy == null ? null : spy.price());
  }

  private Planned planOne(PlayerRow p, AccountRow acc, BigDecimal flow, boolean contribution, Instant now,
                          SessionType session) {
    Balances b = BalanceCalculator.compute(Et.date(now), accounts.ledgerSums(acc.id(), Et.date(now)),
        orders.openBuyHolds(acc.id()), acc.restriction());
    Valuation v = valuer.value(acc);
    BigDecimal equity = v.equity().setScale(Money.SCALE, java.math.RoundingMode.HALF_EVEN);
    List<Warning> warnings = new ArrayList<>();
    // mark-quality gate (§21.2 step 4): only accounts holding positions
    List<String> degradedSymbols = new ArrayList<>();
    DataQuality worst = v.marks().isEmpty() ? null : v.marksQuality();
    String worstBasis = null;
    for (Map.Entry<String, Mark> m : v.marks().entrySet()) {
      Mark mark = m.getValue();
      boolean bad = mark.price() == null || mark.quality() == DataQuality.STALE || mark.quality() == DataQuality.NONE
          || (mark.basis() == MarkBasis.PREV_CLOSE && session != SessionType.CLOSED);
      if (bad) {
        degradedSymbols.add(m.getKey());
      }
      if (mark.basis() != null && (worstBasis == null || bad)) {
        worstBasis = mark.basis().name();
      }
    }
    boolean degraded = !degradedSymbols.isEmpty();
    if (degraded) {
      warnings.add(Warning.of(WarningCode.MARKS_DEGRADED, "Degraded marks for " + String.join(", ", degradedSymbols)
          + "; the unit value at this flow would be set from them."));
    }
    if (equity.compareTo(lowEquityWarning) < 0) {
      warnings.add(Warning.of(WarningCode.ACCOUNT_EQUITY_TOO_LOW, "Equity before the flow is $"
          + equity.toPlainString() + "; the flow still applies (§16.2)."));
    }
    BigDecimal unitsBefore = acc.units();
    BigDecimal u;
    BigDecimal delta = BigDecimal.ZERO.setScale(18);
    int series = acc.unitSeries();
    boolean rebased = false;
    boolean apply = true;
    String skip = null;
    if (flow.signum() < 0 && flow.negate().compareTo(b.withdrawable()) > 0) {
      apply = false;
      skip = SKIP_INSUFFICIENT_WITHDRAWABLE;
    }
    if (unitsBefore.signum() > 0 && equity.signum() > 0) {
      u = UnitMath.unitValue(equity, unitsBefore);
      if (contribution && apply) {
        delta = equity.add(flow).signum() == 0 ? unitsBefore.negate() : UnitMath.unitsFor(flow, u);
      }
    } else if (unitsBefore.signum() == 0) {
      u = accounts.lastTransferUnitValue(acc.id()).orElse(BigDecimal.ONE.setScale(18));
      if (contribution && apply) {
        delta = UnitMath.unitsFor(flow, u);
      }
    } else {
      // wiped out by performance: a contribution rebases the series (§16.2)
      u = BigDecimal.ONE.setScale(18);
      if (contribution && apply && flow.signum() > 0) {
        delta = flow.subtract(unitsBefore).setScale(18);
        series = series + 1;
        rebased = true;
      }
    }
    return new Planned(p, acc, flow, contribution, b, v, u, delta, series, rebased, apply, skip, worst, worstBasis,
        List.copyOf(warnings), degraded, List.copyOf(degradedSymbols));
  }

  private List<PlayerRow> targets(CashTransferCommand c) {
    if (c.scope() == TransferScope.SINGLE) {
      PlayerRow p = accounts.playerByHandle(c.handleOrNull()).orElseThrow(() ->
          ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player " + c.handleOrNull() + "."));
      return List.of(p);
    }
    return accounts.transferTargets(c.kinds());
  }

  private Mark safeMark(String symbol) {
    try {
      Mark m = marketData.mark(symbol);
      return m == null || m.price() == null ? null : m;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
