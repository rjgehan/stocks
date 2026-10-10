package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.CorporateActionType;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.portfolio.EodSnapshotJob;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Corporate actions never move the leaderboard (§16.2–§16.4): the previous EOD is reconstructed with the pre-split
 * holdings even when its snapshot runs after the 03:45 split (catch-up), and the TODAY anchor then sees no gain or
 * loss from a split, a rename or a dividend's ex-date drop. Pinned in {@code golden/performance/split_continuity.json}.
 */
class LeaderboardContinuityTest extends CorporateActionTestSupport {

  @Autowired EodSnapshotJob eod;
  @Autowired PerformanceQueries performance;

  @Test
  void aSplitARenameAndAnExDateCreateNoDayPnl() {
    Player p = human("3000.00");
    String split = symbol();
    String renamed = symbol();
    String newName = "R" + next();
    String div = symbol();
    buy(p, split, "10", "100.00");
    buy(p, renamed, "5", "40.00");
    buy(p, div, "20", "25.00");
    md.dayEndMark(split, FRI, "100.00").dayEndMark(renamed, FRI, "40.00").dayEndMark(div, FRI, "25.00")
        .dayEndMark("SPY", FRI, "500.00");
    String s1 = split(split, MON, "1", "4");
    String s2 = record(renamed, CorporateActionType.NAME_CHANGE, MON, null, null, null, null, newName);
    String s3 = record(div, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.40", WED, null);

    at0345(MON);
    apply(s2);
    apply(s1);
    apply(s3);
    // Friday's EOD runs late (catch-up after the 03:45 actions): holdings at Friday's cut-off are pre-split
    time.set(et("2026-10-12T04:00:00"));
    eod.runFor(FRI);
    var friday = jdbc.sql("SELECT symbol, qty, mark, market_value FROM snapshot_holding"
            + " WHERE account_id = ? AND trade_date = ? ORDER BY symbol")
        .params(p.accountId(), FRI).query().listOfRows();
    assertThat(friday).extracting(r -> r.get("symbol")).containsExactlyInAnyOrder(split, renamed, div);
    assertThat(friday).filteredOn(r -> r.get("symbol").equals(split)).singleElement()
        .satisfies(r -> assertThat((BigDecimal) r.get("qty")).isEqualByComparingTo("10"));

    // Monday 10:00: the post-action prices (a quarter, the same under the new name, minus the dividend)
    time.set(et("2026-10-12T10:00:00"));
    md.withAsset(newName);
    print(split, "25.00", 100);
    print(newName, "40.00", 100);
    print(div, "24.60", 100);
    AccountPnl pnl = performance.accountPnl(p.accountId());
    assertThat(pnl.dayPnl()).as("TODAY P&L").isEqualByComparingTo("0.00");
    assertThat(pnl.dayReturn()).as("TODAY return").isEqualByComparingTo("0");
    Map<String, PositionDayPnl> byPosition = performance.positionDayPnl(p.accountId());
    assertThat(byPosition.get(split).dayPnl()).isEqualByComparingTo("0.00");
    assertThat(byPosition.get(newName).dayPnl()).as("looked up under the old symbol").isEqualByComparingTo("0.00");
    assertThat(byPosition.get(div).dayPnl()).as("the price drop, paid back by the receivable")
        .isEqualByComparingTo("-8.00");

    Map<String, Object> golden = new LinkedHashMap<>();
    golden.put("fridayEodEquity", snapshotEquity(p));
    golden.put("mondayEquity", value(p).equity().toPlainString());
    golden.put("receivable", value(p).dividendsReceivable().toPlainString());
    golden.put("dayPnl", pnl.dayPnl().toPlainString());
    golden.put("unitValueFridayEod", unitValueAtFriday(p));
    golden.put("unitValueNow", pnl.unitValue().stripTrailingZeros().toPlainString());
    GoldenFiles.assertMatches("performance", "split_continuity", golden);
    assertThat(pnl.unitValue()).isEqualByComparingTo(new BigDecimal(unitValueAtFriday(p)));
  }

  private String snapshotEquity(Player p) {
    return jdbc.sql("SELECT equity FROM equity_snapshot WHERE account_id = ? AND trade_date = ?")
        .params(p.accountId(), FRI).query(BigDecimal.class).single().toPlainString();
  }

  private String unitValueAtFriday(Player p) {
    return jdbc.sql("SELECT unit_value FROM equity_snapshot WHERE account_id = ? AND trade_date = ?")
        .params(p.accountId(), FRI).query(BigDecimal.class).single().stripTrailingZeros().toPlainString();
  }
}
