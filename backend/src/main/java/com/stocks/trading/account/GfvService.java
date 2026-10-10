package com.stocks.trading.account;

import com.stocks.common.domain.AccountEventType;
import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.ViolationType;
import com.stocks.common.events.ViolationIssued;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.money.Money;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.GfvDetailDto;
import com.stocks.contract.trading.dto.RestrictionDetailDto;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.engine.EngineTx;
import com.stocks.trading.engine.write.AccountEventWriter;
import com.stocks.trading.engine.write.AccountWriter;
import com.stocks.trading.engine.write.ViolationWriter;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.LotRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Good-faith violations (§10.4) for CASH accounts.
 * <ol>
 *   <li><b>Funding attribution</b> on every BUY: the part of the cost not covered by settled cash is
 *       {@code unsettled_funding} on the new lot, with the latest settle date of the unsettled proceeds used.</li>
 *   <li><b>Detection</b> on every SELL: a relieved lot with unsettled funding whose {@code funding_settle_date} is
 *       after the sell's trade date is a GFV (at most one per sell order).</li>
 *   <li><b>Escalation</b> over active GFVs: ≥ 3 → SETTLED_CASH_ONLY, ≥ 4 → LIQUIDATION_ONLY, for 90 days.</li>
 *   <li><b>Prediction</b> before trading ({@code GFV_RISK}, {@code GFV_ACK_REQUIRED}), on the order's
 *       {@code session_date}.</li>
 * </ol>
 */
@Component
public class GfvService {

  /** {@code unsettled_funding} and {@code funding_settle_date} of a new lot. */
  public record Funding(BigDecimal unsettledFunding, LocalDate fundingSettleDate) {}

  private final AccountStore accounts;
  private final JdbcClient jdbc;
  private final RulesProperties rules;
  private final ViolationWriter violations;
  private final AccountWriter accountWriter;
  private final AccountEventWriter events;
  private final PublicId publicIds;

  public GfvService(AccountStore accounts, JdbcClient jdbc, RulesProperties rules, ViolationWriter violations,
                    AccountWriter accountWriter, AccountEventWriter events, PublicId publicIds) {
    this.accounts = accounts;
    this.jdbc = jdbc;
    this.rules = rules;
    this.violations = violations;
    this.accountWriter = accountWriter;
    this.events = events;
    this.publicIds = publicIds;
  }

  /** Step 1, computed before the buy is posted. */
  public Funding attributeFunding(long accountId, BigDecimal cost, LocalDate asOfEt) {
    BalanceCalculator.LedgerSums s = accounts.ledgerSums(accountId, asOfEt);
    BigDecimal settledAvail = s.cashTotal().subtract(s.unsettledCredits());
    BigDecimal fromSettled = cost.min(settledAvail.max(BigDecimal.ZERO));
    BigDecimal unsettled = cost.subtract(fromSettled).setScale(Money.SCALE);
    if (unsettled.signum() <= 0) {
      return new Funding(Money.ZERO, null);
    }
    LocalDate date = accounts.latestUnsettledCreditDate(accountId, asOfEt).orElse(null);
    if (date == null) {
      return new Funding(Money.ZERO, null);
    }
    return new Funding(unsettled, date);
  }

  /** Step 4: would selling {@code qty} (after {@code skipQty} reserved by other open sells) on {@code tradeDate} be a GFV? */
  public boolean predict(long accountId, String symbol, BigDecimal qty, BigDecimal skipQty, LocalDate tradeDate) {
    if (qty == null || qty.signum() <= 0) {
      return false;
    }
    BigDecimal skip = skipQty == null ? BigDecimal.ZERO : skipQty;
    BigDecimal left = qty;
    for (LotRow lot : accounts.openLots(accountId, symbol)) {
      BigDecimal avail = lot.qtyRemaining();
      if (skip.signum() > 0) {
        BigDecimal used = skip.min(avail);
        skip = skip.subtract(used);
        avail = avail.subtract(used);
      }
      if (avail.signum() <= 0) {
        continue;
      }
      if (violates(lot, tradeDate)) {
        return true;
      }
      left = left.subtract(avail.min(left));
      if (left.signum() <= 0) {
        break;
      }
    }
    return false;
  }

  /** True if relieving this lot on {@code tradeDate} is a good-faith violation. */
  public static boolean violates(LotRow lot, LocalDate tradeDate) {
    return lot.unsettledFunding() != null && lot.unsettledFunding().signum() > 0
        && lot.fundingSettleDate() != null && tradeDate.isBefore(lot.fundingSettleDate());
  }

  /**
   * Step 2 and 3 for a sell execution that relieved {@code violating} (the first lot that violates), inside the
   * engine transaction. Records at most one GFV per order.
   */
  public void recordSell(long accountId, long playerId, long orderId, String orderPublicId, long executionId,
                         String symbol, LotRow violating, LocalDate tradeDate, Instant now, EngineTx tx) {
    int existing = jdbc.sql("SELECT COUNT(*) FROM account_violation WHERE order_id = ? AND violation_type = 'GFV'")
        .param(orderId).query(Integer.class).single();
    if (existing > 0) {
      return;
    }
    String vioId = publicIds.next(Prefix.VIOLATION);
    Instant expires = now.atZone(java.time.ZoneOffset.UTC).plusYears(1).plusDays(1).toInstant();
    violations.insert(vioId, accountId, ViolationType.GFV, orderId, executionId, symbol, violating.unsettledFunding(),
        violating.fundingSettleDate(), tradeDate, now, expires,
        "Sold " + symbol + " bought with unsettled funds before " + violating.fundingSettleDate());
    int active = accounts.activeViolations(accountId, now);
    events.insert(accountId, AccountEventType.GFV, now, symbol, ActorKind.SYSTEM,
        new GfvDetailDto(vioId, orderPublicId, violating.unsettledFunding(), violating.fundingSettleDate(), active));
    AccountRestriction after = escalate(accountId, active, now, tx);
    tx.publish(new ViolationIssued(accountId, playerId, vioId, ViolationType.GFV, after, now));
  }

  /** Raises the restriction if the active count crossed a threshold; never lowers it and never touches FROZEN. */
  AccountRestriction escalate(long accountId, int active, Instant now, EngineTx tx) {
    AccountRow acc = accounts.byId(accountId).orElseThrow();
    AccountRestriction target = restrictionFor(active);
    if (acc.restriction() == AccountRestriction.FROZEN || rank(target) <= rank(acc.restriction())) {
      return acc.restriction();
    }
    Instant until = now.plus(Duration.ofDays(rules.gfv().restrictionDays())).truncatedTo(ChronoUnit.MICROS);
    String reason = active + " good-faith violations";
    accountWriter.setRestriction(accountId, target, until, reason);
    events.insert(accountId, AccountEventType.RESTRICTION, now, null, ActorKind.SYSTEM,
        new RestrictionDetailDto(acc.restriction(), target, until, reason));
    return target;
  }

  /** The GFV restriction for a number of active violations (§10.4 table). */
  public AccountRestriction restrictionFor(int active) {
    if (active >= rules.gfv().liquidationOnlyAt()) {
      return AccountRestriction.LIQUIDATION_ONLY;
    }
    if (active >= rules.gfv().settledCashOnlyAt()) {
      return AccountRestriction.SETTLED_CASH_ONLY;
    }
    return AccountRestriction.NONE;
  }

  /** Active GFVs and the {@code restriction_until} they imply: the crossing violation's time + restriction days. */
  public record Recomputed(AccountRestriction restriction, Instant until) {}

  /** Recomputes the GFV restriction from {@code account_violation} (unfreeze and daily maintenance, §10.4). */
  public Recomputed recompute(long accountId, Instant now) {
    List<Instant> active = jdbc.sql("SELECT occurred_at FROM account_violation WHERE account_id = ? AND expires_at > ?"
            + " ORDER BY occurred_at")
        .params(accountId, com.stocks.common.jdbc.Jdbc.ts(now))
        .query((rs, n) -> com.stocks.common.jdbc.Jdbc.instant(rs, 1)).list();
    AccountRestriction r = restrictionFor(active.size());
    if (r == AccountRestriction.NONE) {
      return new Recomputed(r, null);
    }
    int crossingIndex = (r == AccountRestriction.LIQUIDATION_ONLY ? rules.gfv().liquidationOnlyAt()
        : rules.gfv().settledCashOnlyAt()) - 1;
    Instant until = active.get(crossingIndex).plus(Duration.ofDays(rules.gfv().restrictionDays()));
    if (!until.isAfter(now)) {
      return new Recomputed(AccountRestriction.NONE, null);
    }
    return new Recomputed(r, until);
  }

  private static int rank(AccountRestriction r) {
    return switch (r) {
      case NONE -> 0;
      case SETTLED_CASH_ONLY -> 1;
      case LIQUIDATION_ONLY -> 2;
      case FROZEN -> 3;
    };
  }
}
