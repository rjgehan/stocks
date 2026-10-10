package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.contract.portfolio.UnitFlow;
import com.stocks.contract.portfolio.UnitMath;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The §16.2 golden vectors ({@code golden/performance/}) replayed through the pure arithmetic: {@link UnitMath} for
 * openings, flows and resets, {@link MetricsCalculator} for TWR. Values are compared at 10 dp (stored at 18). The same
 * scenarios run end to end through the real services in {@code PerformanceScenariosTest}.
 */
class PerformanceGoldenTest {

  /** One account's unit ledger, as the engine keeps it on {@code account}. */
  static final class Ledger {
    BigDecimal units = BigDecimal.ZERO;
    BigDecimal nc = BigDecimal.ZERO;
    int series = 1;
    BigDecimal lastU;
    BigDecimal closedRunsPnl = BigDecimal.ZERO;
  }

  record Point(UnitPoint unit, BigDecimal cumulativePnl) {}

  @ParameterizedTest
  @ValueSource(strings = {"twr_deposit", "twr_deposit_all", "twr_tiny_equity", "twr_reset"})
  void vector(String name) {
    JsonNode golden = GoldenFiles.read("performance", name);
    Map<String, Ledger> ledgers = new HashMap<>();
    Map<String, Point> points = new HashMap<>();
    for (JsonNode step : golden.get("steps")) {
      String account = step.get("account").asString();
      Ledger l = ledgers.computeIfAbsent(account, k -> new Ledger());
      JsonNode expect = step.get("expect");
      Point point;
      switch (step.get("op").asString()) {
        case "OPEN" -> {
          BigDecimal f = dec(step, "amount");
          UnitFlow open = UnitMath.openRun(f, null, 0);
          l.units = open.unitsAfter();
          l.nc = f;
          l.series = open.unitSeriesAfter();
          l.lastU = open.unitValue();
          point = new Point(new UnitPoint(UnitMath.unitValue(f, l.units), l.series), l.closedRunsPnl);
        }
        case "MARK" -> {
          BigDecimal e = dec(step, "equity");
          point = new Point(new UnitPoint(UnitMath.unitValueOrLast(e, l.units, l.lastU), l.series),
              l.closedRunsPnl.add(e).subtract(l.nc));
        }
        case "FLOW" -> {
          BigDecimal before = dec(step, "equityBefore");
          BigDecimal f = dec(step, "amount");
          UnitFlow flow = UnitMath.contribution(f, before, l.units, l.series, l.lastU);
          assertThat(flow.unitsAfter()).isEqualByComparingTo(l.units.add(flow.unitsDelta()));
          l.units = flow.unitsAfter();
          l.nc = l.nc.add(f);
          l.series = flow.unitSeriesAfter();
          l.lastU = flow.unitValue();
          if (expect != null && expect.has("unitsDelta")) {
            assertThat(ten(flow.unitsDelta())).as("%s unitsDelta", step).isEqualByComparingTo(dec(expect, "unitsDelta"));
          }
          if (expect != null && expect.has("rebased")) {
            assertThat(flow.rebased()).as("%s rebased", step).isEqualTo(expect.get("rebased").asBoolean());
          }
          BigDecimal after = before.add(f);
          point = new Point(new UnitPoint(UnitMath.unitValueOrLast(after, l.units, l.lastU), l.series),
              l.closedRunsPnl.add(after).subtract(l.nc));
          assertThat(ten(point.unit().unitValue())).as("a flow never changes U (except a rebase)")
              .isEqualByComparingTo(ten(flow.unitValue()));
        }
        case "RESET" -> {
          BigDecimal close = dec(step, "equity");
          BigDecimal finalU = UnitMath.unitValueOrLast(close, l.units, l.lastU);
          if (expect != null && expect.has("finalUnitValue")) {
            assertThat(ten(finalU)).isEqualByComparingTo(dec(expect, "finalUnitValue"));
          }
          l.closedRunsPnl = l.closedRunsPnl.add(close).subtract(l.nc);
          BigDecimal f = dec(step, "amount");
          UnitFlow open = UnitMath.openRun(f, finalU, l.series);
          l.units = open.unitsAfter();
          l.nc = f;
          l.series = open.unitSeriesAfter();
          l.lastU = open.unitValue();
          point = new Point(new UnitPoint(open.unitValue(), l.series), l.closedRunsPnl);
        }
        default -> throw new IllegalArgumentException(step.toString());
      }
      if (expect != null) {
        if (expect.has("units")) {
          assertThat(ten(l.units)).as("%s units", step).isEqualByComparingTo(dec(expect, "units"));
        }
        if (expect.has("unitValue")) {
          assertThat(ten(point.unit().unitValue())).as("%s U", step).isEqualByComparingTo(dec(expect, "unitValue"));
        }
        if (expect.has("unitSeries")) {
          assertThat(l.series).as("%s series", step).isEqualTo(expect.get("unitSeries").asInt());
        }
      }
      points.put(account + "/" + step.get("label").asString(), point);
    }
    for (JsonNode w : golden.get("windows")) {
      String account = w.get("account").asString();
      Point from = points.get(account + "/" + w.get("from").asString());
      Point to = points.get(account + "/" + w.get("to").asString());
      assertThat(MetricsCalculator.twr(from.unit(), to.unit())).as("%s twr", w).isEqualByComparingTo(dec(w, "twr"));
      assertThat(to.cumulativePnl().subtract(from.cumulativePnl())).as("%s pnl", w)
          .isEqualByComparingTo(dec(w, "pnl"));
      assertThat(from.unit().unitSeries() != to.unit().unitSeries()).as("%s rebased", w)
          .isEqualTo(w.get("rebased").asBoolean());
    }
  }

  static BigDecimal dec(JsonNode node, String field) {
    return new BigDecimal(node.get(field).asString());
  }

  static BigDecimal ten(BigDecimal v) {
    return v.setScale(10, RoundingMode.HALF_EVEN);
  }
}
