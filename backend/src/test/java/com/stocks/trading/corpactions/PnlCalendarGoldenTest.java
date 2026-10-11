package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferType;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto.SymbolPnlDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.PnlCalendarCellDto;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.portfolio.EodSnapshotJob;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §16.10 P&amp;L calendar through the real engine (golden {@code performance/pnl_calendar_split_deposit.json}): a
 * 1-for-4 split applied at 03:45 on Monday's ex-date and a $1,000 admin deposit on Tuesday. Monday's cell is exactly
 * $0.00 with an unchanged unit value (a split is not P&amp;L); Tuesday's cell is only the price move, the deposit is in
 * {@code flows}; Wednesday is live. The by-symbol split of the same window reconciles to the run's P&amp;L.
 */
class PnlCalendarGoldenTest extends CorporateActionTestSupport {

  @Autowired EodSnapshotJob eod;
  @Autowired PnlAnalysisQueries analysis;

  @Test
  void aSplitDayIsZeroAndADepositNeverCounts() {
    Player p = human("3000.00");
    String spl = symbol();
    buy(p, spl, "10", "100.00");
    md.dayEndMark(spl, FRI, "101.00").dayEndMark("SPY", FRI, "500.00");
    time.set(et("2026-10-09T20:20:00"));
    eod.runFor(FRI);

    String split = split(spl, MON, "1", "4");
    at0345(MON);
    apply(split);
    md.dayEndMark(spl, MON, "25.25").dayEndMark("SPY", MON, "500.00");
    time.set(et("2026-10-12T20:20:00"));
    eod.runFor(MON);

    time.set(et("2026-10-13T11:00:00"));
    print(spl, "25.50", 100);
    long admin = fx.admin("root" + next()).id();
    transfers.apply(new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(), TransferType.DEPOSIT,
        new BigDecimal("1000.00"), null, "top-up", false, false), null, admin);
    md.dayEndMark(spl, TUE, "26.00").dayEndMark("SPY", TUE, "500.00");
    time.set(et("2026-10-13T20:20:00"));
    eod.runFor(TUE);

    time.set(et("2026-10-14T11:00:00"));
    print(spl, "26.25", 100);
    PnlCalendarDto oct = analysis.calendar(p.accountId(), CalendarView.DAY, YearMonth.of(2026, 10), null);
    assertThat(oct.cells()).extracting(PnlCalendarCellDto::date).containsExactly(FRI, MON, TUE, WED);
    PnlCalendarCellDto mon = oct.cells().get(1);
    assertThat(mon.pnl()).as("a split is not P&L").isEqualByComparingTo("0.00");
    assertThat(mon.ret()).as("U unchanged across the split").isEqualByComparingTo("0");
    PnlCalendarCellDto tue = oct.cells().get(2);
    assertThat(tue.pnl()).as("40 × (26.00 − 25.25); the deposit never counts").isEqualByComparingTo("30.00");
    assertThat(tue.flows()).isEqualByComparingTo("1000.00");
    PnlCalendarCellDto wed = oct.cells().get(3);
    assertThat(wed.live()).isTrue();
    assertThat(wed.pnl()).isEqualByComparingTo("10.00");

    PnlAnalysisDto all = analysis.analysis(p.accountId(), HistoryRange.ALL);
    BigDecimal sum = all.other();
    for (SymbolPnlDto s : all.bySymbol()) {
      sum = sum.add(s.total());
    }
    assertThat(sum).as("Σ total + other = pnl").isEqualByComparingTo(all.pnl());
    assertThat(all.bySymbol()).singleElement().satisfies(s -> {
      assertThat(s.symbol()).isEqualTo(spl);
      assertThat(s.held()).isTrue();
    });

    Map<String, Object> golden = new LinkedHashMap<>();
    List<Map<String, Object>> cells = new ArrayList<>();
    for (PnlCalendarCellDto c : oct.cells()) {
      Map<String, Object> cell = new LinkedHashMap<>();
      cell.put("date", c.date().toString());
      cell.put("pnl", c.pnl().toPlainString());
      cell.put("return", c.ret().toPlainString());
      cell.put("flows", c.flows().toPlainString());
      cell.put("live", c.live());
      cells.add(cell);
    }
    golden.put("cells", cells);
    golden.put("totalPnl", oct.total().pnl().toPlainString());
    golden.put("totalReturn", oct.total().ret().toPlainString());
    golden.put("runPnl", all.pnl().toPlainString());
    GoldenFiles.assertMatches("performance", "pnl_calendar_split_deposit", golden);
  }
}
