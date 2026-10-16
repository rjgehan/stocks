package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.portfolio.dto.LeaderboardDto.BenchmarkPlayerDto;
import com.stocks.league.LeaderboardComputer.PeriodBoard;
import com.stocks.portfolio.MetricsCalculator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPY as a player: the starting cash bought SPY at the league's opening and held it. */
class BenchmarkPlayerTest {

  static PeriodBoard board(LeaderboardPeriod period, String start, String end) {
    BigDecimal a = start == null ? null : new BigDecimal(start);
    BigDecimal b = end == null ? null : new BigDecimal(end);
    return new PeriodBoard(period, null, null, MetricsCalculator.ratioMinusOne(b, a), List.of(), a, b);
  }

  @Test
  void equityAndPnlFollowTheIndexFromTheOpening() {
    BigDecimal cash = new BigDecimal("500000.00");
    // opened at 500, the week started at 520, today at 540, now 550
    BenchmarkPlayerDto spy = LeaderboardServiceImpl.benchmarkPlayer("SPY", cash,
        board(LeaderboardPeriod.WEEK, "520", "550"), board(LeaderboardPeriod.TODAY, "540", "550"),
        board(LeaderboardPeriod.ALL, "500", "550"));
    assertThat(spy.symbol()).isEqualTo("SPY");
    assertThat(spy.equity()).isEqualByComparingTo("550000.00");
    assertThat(spy.pnl()).isEqualByComparingTo("30000.00");
    assertThat(spy.dayPnl()).isEqualByComparingTo("10000.00");
    assertThat(spy.totalPnl()).isEqualByComparingTo("50000.00");
    assertThat(spy.totalReturn()).isEqualByComparingTo("0.1");
    assertThat(spy.dayReturn()).isEqualByComparingTo(new BigDecimal("550").divide(new BigDecimal("540"), 10,
        java.math.RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE));
  }

  @Test
  void anUnknownIndexLeavesTheFiguresNull() {
    BenchmarkPlayerDto spy = LeaderboardServiceImpl.benchmarkPlayer("SPY", new BigDecimal("500000.00"),
        board(LeaderboardPeriod.WEEK, null, "550"), board(LeaderboardPeriod.TODAY, "540", "550"),
        board(LeaderboardPeriod.ALL, null, "550"));
    assertThat(spy.equity()).isNull();
    assertThat(spy.pnl()).isNull();
    assertThat(spy.dayPnl()).isNull();                                  // no opening value to scale by
    assertThat(spy.ret()).isNull();
  }
}
