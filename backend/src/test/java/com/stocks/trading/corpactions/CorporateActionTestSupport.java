package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared helpers of the corporate-action engine tests (§14): buy shares, record a provider action through the sync
 * job's upsert, apply it on the engine, and read back lots, adjustments and activities. The test week is the §0
 * week: Fri 2026-10-09 (start), Mon 2026-10-12 (Columbus Day, NYSE open), Wed 2026-10-14.
 */
abstract class CorporateActionTestSupport extends TradingTestBase {

  static final LocalDate FRI = LocalDate.of(2026, 10, 9);
  static final LocalDate MON = LocalDate.of(2026, 10, 12);
  static final LocalDate TUE = LocalDate.of(2026, 10, 13);
  static final LocalDate WED = LocalDate.of(2026, 10, 14);

  @Autowired CorporateActionSyncJob sync;
  @Autowired CorporateActionStore store;
  @Autowired ValuationService valuation;

  /** Buys {@code qty} at {@code ask} with a MARKET order now (regular session). */
  void buy(Player p, String symbol, String qty, String ask) {
    BigDecimal a = new BigDecimal(ask);
    String bid = a.subtract(new BigDecimal("0.02")).max(new BigDecimal("0.01")).toPlainString();
    quote(symbol, bid, 100_000, ask, 100_000);
    OrderDto o = place(p, market(cid(), symbol, Side.BUY, qty)).order();
    time.advance(Duration.ofSeconds(1));
    quote(symbol, bid, 100_000, ask, 100_000);
    step();
    assertThat(order(p, o.orderId()).status()).as("buy %s %s", qty, symbol).isEqualTo(OrderStatus.FILLED);
  }

  /** Records a provider action through the sync upsert; returns its public id. */
  String record(String symbol, CorporateActionType type, LocalDate exDate, String oldRate, String newRate,
                String cashRate, LocalDate payableDate, String newSymbol) {
    String ref = "test:" + next();
    sync.upsert(List.of(new CorporateActionData(ref, symbol, type, exDate, exDate, payableDate, dec(oldRate),
        dec(newRate), dec(cashRate), newSymbol)));
    return store.byProviderRef(ref).orElseThrow().publicId();
  }

  String split(String symbol, LocalDate exDate, String oldRate, String newRate) {
    CorporateActionType type = new BigDecimal(newRate).compareTo(new BigDecimal(oldRate)) > 0
        ? CorporateActionType.FORWARD_SPLIT : CorporateActionType.REVERSE_SPLIT;
    return record(symbol, type, exDate, oldRate, newRate, null, null, null);
  }

  CorporateActionRow apply(String actionId) {
    return engine.run(new EngineCommand.ApplyCorporateActionCmd(actionId));
  }

  CorporateActionRow row(String actionId) {
    return store.byPublicId(actionId).orElseThrow();
  }

  /** 03:45 ET on {@code date}: the apply job's time. */
  void at0345(LocalDate date) {
    time.set(et(date + "T03:45:00"));
  }

  List<Map<String, Object>> lots(long accountId, String symbol) {
    return jdbc.sql("SELECT id, qty_original, qty_remaining, cost_remaining, closed_at FROM position_lot"
            + " WHERE account_id = ? AND symbol = ? ORDER BY id")
        .params(accountId, symbol).query().listOfRows();
  }

  List<Map<String, Object>> adjustments(long accountId) {
    return jdbc.sql("SELECT symbol_before, symbol_after, qty_before, qty_after, cost_before, cost_after,"
            + " cil_ledger_id FROM position_adjustment WHERE account_id = ? ORDER BY id")
        .param(accountId).query().listOfRows();
  }

  List<ActivityDto> activities(Player p, ActivityType... types) {
    return accountQueries.activities(p.accountId(), new ActivityFilter(Set.of(types), null, null), null, 50)
        .items();
  }

  Valuation value(Player p) {
    return valuation.valueNow(p.accountId());
  }

  /** {@code U = E / units} at scale 10 (§16.2), for continuity checks. */
  BigDecimal unitValue(Player p) {
    Valuation v = value(p);
    return v.equity().divide(v.units(), 10, RoundingMode.HALF_EVEN);
  }

  static BigDecimal dec(String s) {
    return s == null ? null : new BigDecimal(s);
  }

  Instant now() {
    return time.now();
  }
}
