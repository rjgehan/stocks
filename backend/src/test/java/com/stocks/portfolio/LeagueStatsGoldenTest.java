package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.Side;
import com.stocks.contract.portfolio.UnitFlow;
import com.stocks.contract.portfolio.UnitMath;
import com.stocks.portfolio.ClosedLots.Adjustment;
import com.stocks.portfolio.ClosedLots.Event;
import com.stocks.portfolio.ClosedLots.Execution;
import com.stocks.portfolio.ClosedLots.Lot;
import com.stocks.portfolio.ClosedLots.Summary;
import com.stocks.portfolio.MetricsCalculator.RiskStats;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §16.8 golden vectors ({@code golden/performance/sharpe.json}, {@code closed_lots.json}), computed independently
 * (Python {@code decimal}, 34 digits HALF_EVEN) and replayed here through the pure arithmetic: {@link UnitMath} for
 * the run and its deposit, {@link MetricsCalculator} for daily returns, Sharpe, volatility, win rate and profit factor,
 * {@link ClosedLots} for the FIFO closed lots.
 */
class LeagueStatsGoldenTest {

  static BigDecimal dec(JsonNode n, String field) {
    return new BigDecimal(n.get(field).asString());
  }

  static BigDecimal ten(BigDecimal v) {
    return v.setScale(10, RoundingMode.HALF_EVEN);
  }

  // ------------------------------------------------------------------------------------------- Sharpe

  /** The opening (U = 1) then one U per EOD, with the deposit applied at its pre-flow U. */
  static List<UnitPoint> path(JsonNode g, int eods) {
    BigDecimal shares = dec(g, "shares");
    UnitFlow open = UnitMath.openRun(dec(g, "openingCash"), null, 0);
    BigDecimal units = open.unitsAfter();
    BigDecimal cash = dec(g, "openingCash").subtract(shares.multiply(dec(g, "buyPrice")));
    JsonNode dep = g.get("deposit");
    List<UnitPoint> out = new ArrayList<>();
    out.add(new UnitPoint(open.unitValue(), open.unitSeriesAfter()));
    JsonNode rows = g.get("eods");
    for (int i = 0; i < eods; i++) {
      if (i == dep.get("afterEod").asInt()) {
        BigDecimal before = cash.add(shares.multiply(dec(dep, "mark")));
        assertThat(before).isEqualByComparingTo(dec(dep, "equityBefore"));
        UnitFlow flow = UnitMath.contribution(dec(dep, "amount"), before, units, 1, BigDecimal.ONE);
        assertThat(flow.unitValue()).as("the deposit buys units at the pre-flow U")
            .isEqualByComparingTo(dec(dep, "unitValue"));
        assertThat(flow.unitsDelta()).isEqualByComparingTo(dec(dep, "unitsDelta"));
        BigDecimal after = UnitMath.unitValue(before.add(dec(dep, "amount")), flow.unitsAfter());
        assertThat(ten(after)).as("a deposit never moves U").isEqualByComparingTo(ten(flow.unitValue()));
        units = flow.unitsAfter();
        cash = cash.add(dec(dep, "amount"));
      }
      BigDecimal equity = cash.add(shares.multiply(dec(rows.get(i), "close")));
      assertThat(equity).isEqualByComparingTo(dec(rows.get(i), "equity"));
      BigDecimal u = UnitMath.unitValue(equity, units);
      assertThat(u).as("EOD %d", i + 1).isEqualByComparingTo(dec(rows.get(i), "unitValue"));
      out.add(new UnitPoint(u, 1));
    }
    return out;
  }

  @Test
  void sharpeAndVolatilityOfTwentyFourDailyReturnsWithADepositInTheMiddle() {
    JsonNode g = GoldenFiles.read("performance", "sharpe");
    JsonNode expected = g.get("expected");
    List<UnitPoint> path = path(g, g.get("eods").size());
    List<BigDecimal> returns = MetricsCalculator.dailyReturns(path);
    assertThat(returns).hasSize(expected.get("dailyReturnCount").asInt());
    for (int i = 0; i < returns.size(); i++) {
      assertThat(ten(returns.get(i))).as("r%d", i + 1)
          .isEqualByComparingTo(new BigDecimal(expected.get("dailyReturns").get(i).asString()));
    }
    RiskStats risk = MetricsCalculator.riskOfPath(path);
    assertThat(risk.dailyReturns()).isEqualTo(24);
    assertThat(risk.sharpe()).isEqualByComparingTo(dec(expected, "sharpe"));
    assertThat(risk.sharpe().scale()).isEqualTo(MetricsCalculator.RATIO_SCALE);
    assertThat(risk.volatility()).isEqualByComparingTo(dec(expected, "volatility"));
    assertThat(risk.volatility().scale()).isEqualTo(MetricsCalculator.RETURN_SCALE);
  }

  @Test
  void nineteenDailyReturnsAreBelowTheMinimum() {
    JsonNode g = GoldenFiles.read("performance", "sharpe");
    JsonNode s = g.get("short");
    RiskStats risk = MetricsCalculator.riskOfPath(path(g, s.get("eods").asInt()));
    assertThat(risk.dailyReturns()).isEqualTo(s.get("dailyReturnCount").asInt()).isEqualTo(19);
    assertThat(s.get("sharpe").isNull()).isTrue();
    assertThat(risk.sharpe()).isNull();
    assertThat(risk.volatility()).isNull();
  }

  @Test
  void flatReturnsHaveNoSharpeARebaseIsMinus100AndAWipedOutDayIsFlat() {
    List<UnitPoint> flat = new ArrayList<>(Collections.nCopies(25, new UnitPoint(BigDecimal.ONE, 1)));
    RiskStats risk = MetricsCalculator.riskOfPath(flat);
    assertThat(risk.dailyReturns()).isEqualTo(24);
    assertThat(risk.sharpe()).as("sd = 0").isNull();
    assertThat(risk.volatility()).isNull();
    List<BigDecimal> r = MetricsCalculator.dailyReturns(List.of(new UnitPoint(new BigDecimal("0.5"), 1),
        new UnitPoint(BigDecimal.ONE, 2), new UnitPoint(BigDecimal.ZERO, 2), new UnitPoint(BigDecimal.ONE, 2)));
    assertThat(r).hasSize(3);
    assertThat(r.get(0)).as("across a rebase").isEqualByComparingTo("-1");
    assertThat(r.get(1)).isEqualByComparingTo("-1");
    assertThat(r.get(2)).as("U = 0 cannot move without a flow").isEqualByComparingTo("0");
    assertThat(MetricsCalculator.riskOfPath(List.of())).isEqualTo(new RiskStats(0, null, null));
  }

  // ------------------------------------------------------------------------------------------- closed lots

  static List<Event> events(JsonNode g) {
    List<Event> out = new ArrayList<>();
    for (JsonNode e : g.get("events")) {
      Instant at = Instant.parse(e.get("at").asString());
      switch (e.get("op").asString()) {
        case "BUY", "SELL" -> out.add(new Execution(e.get("id").asLong(), e.get("symbol").asString(),
            Side.valueOf(e.get("op").asString()), dec(e, "qty"), dec(e, "netAmount"),
            e.get("realizedPnl").isNull() ? null : dec(e, "realizedPnl"), at));
        case "ADJUST" -> out.add(new Adjustment(e.get("id").asLong(), e.get("lot").asLong(),
            e.get("symbolAfter").asString(), dec(e, "qtyAfter"), dec(e, "costAfter"), at));
        default -> throw new IllegalArgumentException(e.toString());
      }
    }
    return out;
  }

  @Test
  void closedLotsFifoAcrossASplitAddUpToEachSellsRealizedPnl() {
    JsonNode g = GoldenFiles.read("performance", "closed_lots");
    JsonNode expected = g.get("expected");
    List<Event> ev = events(g);
    List<Event> shuffled = new ArrayList<>(ev);
    Collections.reverse(shuffled);                       // replay order comes from the events, not the input order
    List<Lot> lots = ClosedLots.replay(shuffled);
    JsonNode want = expected.get("lots");
    assertThat(lots).hasSize(want.size());
    for (int i = 0; i < lots.size(); i++) {
      Lot l = lots.get(i);
      JsonNode w = want.get(i);
      assertThat(l.buyExecutionId()).as("lot %d buy", i).isEqualTo(w.get("buyExecutionId").asLong());
      assertThat(l.sellExecutionId()).as("lot %d sell", i).isEqualTo(w.get("sellExecutionId").asLong());
      assertThat(l.qty()).isEqualByComparingTo(dec(w, "qty"));
      assertThat(l.openedAt()).isEqualTo(Instant.parse(w.get("openedAt").asString()));
      assertThat(l.closedAt()).isEqualTo(Instant.parse(w.get("closedAt").asString()));
      assertThat(l.holdSeconds()).isEqualTo(w.get("holdSeconds").asLong());
      assertThat(l.cost()).as("lot %d cost", i).isEqualByComparingTo(dec(w, "cost"));
      assertThat(l.proceeds()).as("lot %d proceeds", i).isEqualByComparingTo(dec(w, "proceeds"));
      assertThat(l.realizedPnl()).as("lot %d pnl", i).isEqualByComparingTo(dec(w, "realizedPnl"));
      assertThat(l.ret()).isEqualByComparingTo(dec(w, "return"));
    }
    // the slices of each sell add up to the ledger's realized P&L
    for (Event e : ev) {
      if (e instanceof Execution x && x.side() == Side.SELL) {
        BigDecimal sum = lots.stream().filter(l -> l.sellExecutionId() == x.id()).map(Lot::realizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).as("sell %d", x.id()).isEqualByComparingTo(x.realizedPnl());
      }
    }
    Summary all = ClosedLots.summarize(lots, Instant.parse("2026-10-10T00:00:00Z"));
    assertThat(all.count()).isEqualTo(4);
    assertThat(all.avgHoldSeconds()).isEqualTo(expected.get("avgHoldSeconds").asLong());
    assertThat(all.best().buyExecutionId()).isEqualTo(expected.get("best").get("buyExecutionId").asLong());
    assertThat(all.best().sellExecutionId()).isEqualTo(expected.get("best").get("sellExecutionId").asLong());
    assertThat(all.worst().buyExecutionId()).isEqualTo(expected.get("worst").get("buyExecutionId").asLong());
    assertThat(all.worst().sellExecutionId()).isEqualTo(expected.get("worst").get("sellExecutionId").asLong());
    Summary thursday = ClosedLots.summarize(lots, Instant.parse("2026-10-08T23:59:59Z"));
    assertThat(thursday.count()).as("count and hold use every lot").isEqualTo(4);
    assertThat(thursday.worst().sellExecutionId()).as("Friday's sell is not visible yet")
        .isEqualTo(expected.get("worstBeforeFriday").get("sellExecutionId").asLong());
    assertThat(ClosedLots.summarize(List.of(), Instant.EPOCH)).isEqualTo(Summary.NONE);

    // win rate and profit factor count closing sells
    assertThat(MetricsCalculator.winRate(expected.get("wins").asInt(), expected.get("closingSells").asInt()))
        .isEqualByComparingTo(dec(expected, "winRate"));
    BigDecimal pf = MetricsCalculator.profitFactor(dec(expected, "grossProfit"), dec(expected, "grossLoss"));
    assertThat(pf).isEqualByComparingTo(dec(expected, "profitFactor"));
    assertThat(pf.scale()).isEqualTo(4);
    assertThat(MetricsCalculator.profitFactor(new BigDecimal("10.00"), BigDecimal.ZERO)).as("no loss").isNull();
    assertThat(MetricsCalculator.profitFactor(null, BigDecimal.ONE)).isNull();
  }

  @Test
  void aLotThatACorporateActionClosesIsNotATradeAndARenameMovesTheLot() {
    Instant t0 = Instant.parse("2026-10-05T14:00:00Z");
    List<Event> ev = List.of(
        new Execution(1, "OLD", Side.BUY, new BigDecimal("3"), new BigDecimal("-30.00"), null, t0),
        new Execution(2, "DUST", Side.BUY, new BigDecimal("0.4"), new BigDecimal("-4.00"), null, t0),
        new Adjustment(10, 2, "DUST", BigDecimal.ZERO, BigDecimal.ZERO, t0.plusSeconds(3600)),
        new Adjustment(11, 1, "NEW", new BigDecimal("3"), new BigDecimal("30.00"), t0.plusSeconds(3600)),
        new Adjustment(12, 99, "X", BigDecimal.ONE, BigDecimal.ONE, t0.plusSeconds(3600)),     // unknown lot
        new Execution(3, "NEW", Side.SELL, new BigDecimal("3"), new BigDecimal("36.00"), new BigDecimal("6.00"),
            t0.plusSeconds(7200)));
    List<Lot> lots = ClosedLots.replay(ev);
    assertThat(lots).singleElement().satisfies(l -> {
      assertThat(l.symbol()).isEqualTo("NEW");
      assertThat(l.realizedPnl()).isEqualByComparingTo("6.00");
      assertThat(l.cost()).isEqualByComparingTo("30.00");
    });
    Lot zero = new Lot(1, 2, "Z", BigDecimal.ONE, t0, t0, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
    assertThat(zero.ret()).isNull();
  }
}
