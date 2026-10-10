package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.Print;
import com.stocks.testkit.GoldenFiles;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden fills (§12.4, §12.5): the pure {@link FillModel} on a fixed set of orders and quotes, compared with
 * {@code src/test/resources/golden/engine/fill-model.json} (WS3, §27.2). Regenerate with {@code -Dgolden.update=true}
 * and review the diff. The explicit assertions pin the rules the golden file encodes.
 */
class FillModelGoldenTest {

  static final BigDecimal COLLAR = new BigDecimal("5");

  static ExecQuote q(String bid, long bidSize, String ask, long askSize) {
    return new ExecQuote("XYZ", new BigDecimal(bid), bidSize, new BigDecimal(ask), askSize, null, TestTimes.START,
        TestTimes.START, DataQuality.REALTIME_CONSOLIDATED, "golden", false);
  }

  static FillModel.OrderView o(Side side, OrderType type, boolean fractional, String qty, String limit) {
    return new FillModel.OrderView(side, type, fractional, new BigDecimal(qty),
        limit == null ? null : new BigDecimal(limit), null);
  }

  static Print p(String price, long size) {
    return new Print(new BigDecimal(price), size, TestTimes.START, TestTimes.START, true);
  }

  @Test
  void goldenDecisions() {
    Map<String, FillModel.Decision> cases = new LinkedHashMap<>();
    cases.put("marketBuyWithinDisplayedSize",
        FillModel.marketable(o(Side.BUY, OrderType.MARKET, false, "50", null), q("227.40", 300, "227.42", 200),
            false, COLLAR));
    cases.put("marketBuyWalksTenLevelsMax",
        FillModel.marketable(o(Side.BUY, OrderType.MARKET, false, "5000", null), q("49.98", 100, "50.00", 100),
            false, COLLAR));
    cases.put("marketSellWalksDownWithWideSpread",
        FillModel.marketable(o(Side.SELL, OrderType.MARKET, false, "450", null), q("9.00", 100, "10.00", 100),
            false, COLLAR));
    cases.put("marketBuyZeroShownSizeCountsAs100",
        FillModel.marketable(o(Side.BUY, OrderType.MARKET, false, "150", null), q("19.99", 0, "20.00", 0),
            false, COLLAR));
    cases.put("queuedMarketBuyAtTheOpen",
        FillModel.marketable(o(Side.BUY, OrderType.MARKET, false, "10", null), q("99.98", 300, "100.00", 300),
            true, COLLAR));
    cases.put("fractionalBuyOneFillAtTouch",
        FillModel.marketable(o(Side.BUY, OrderType.MARKET, true, "2.19857", null), q("227.40", 1, "227.42", 1),
            false, COLLAR));
    cases.put("subDollarBuyWalksInTenThousandths",
        FillModel.marketable(o(Side.BUY, OrderType.MARKET, false, "250", null), q("0.8498", 100, "0.8500", 100),
            false, COLLAR));
    cases.put("marketableLimitBuyStopsAtLimit",
        FillModel.marketable(o(Side.BUY, OrderType.LIMIT, false, "300", "50.01"), q("49.98", 100, "50.00", 100),
            false, COLLAR));
    cases.put("nonMarketableLimitRests",
        FillModel.marketable(o(Side.BUY, OrderType.LIMIT, false, "10", "49.00"), q("49.98", 100, "50.00", 100),
            false, COLLAR));
    cases.put("restingBuyQuoteCrossFillsAtLimit",
        FillModel.resting(o(Side.BUY, OrderType.LIMIT, false, "500", "95.00"), q("94.80", 100, "94.90", 20),
            List.of(), false));
    cases.put("restingSellTradeThroughPrintsOnly",
        FillModel.resting(o(Side.SELL, OrderType.LIMIT, false, "100", "51.00"), q("50.90", 100, "50.95", 100),
            List.of(p("51.00", 500), p("51.05", 30), p("51.10", 25)), false));
    cases.put("restingRequiresPrintIgnoresTheQuote",
        FillModel.resting(o(Side.BUY, OrderType.LIMIT, false, "10", "95.00"), q("94.80", 100, "94.90", 100),
            List.of(), true));
    GoldenFiles.assertMatches("engine", "fill-model", cases);

    FillModel.Decision walk = cases.get("marketBuyWalksTenLevelsMax");
    assertThat(walk.legs()).hasSize(10);
    assertThat(walk.legs().get(0).liquidity()).isEqualTo(Liquidity.TOUCH);
    assertThat(walk.legs().get(9).price()).isEqualByComparingTo("50.09");
    assertThat(walk.filledQty()).isEqualByComparingTo("1000");
    assertThat(walk.beyondCollar()).isTrue();
    assertThat(walk.collar()).isEqualByComparingTo("52.50");

    FillModel.Decision sell = cases.get("marketSellWalksDownWithWideSpread");
    assertThat(sell.collar()).isEqualByComparingTo("8.55");
    assertThat(sell.legs()).extracting(FillModel.Leg::price).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("9.00"));                                  // next level 8.50 is below the 8.55 collar
    assertThat(sell.beyondCollar()).isTrue();

    assertThat(cases.get("queuedMarketBuyAtTheOpen").legs().get(0).liquidity()).isEqualTo(Liquidity.OPEN);
    assertThat(cases.get("fractionalBuyOneFillAtTouch").legs()).singleElement()
        .satisfies(l -> assertThat(l.qty()).isEqualByComparingTo("2.19857"));
    assertThat(cases.get("nonMarketableLimitRests").legs()).isEmpty();
    assertThat(cases.get("nonMarketableLimitRests").rest()).isTrue();
    assertThat(cases.get("marketableLimitBuyStopsAtLimit").legs()).extracting(FillModel.Leg::price)
        .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("50.00"), new BigDecimal("50.01"));
    assertThat(cases.get("marketableLimitBuyStopsAtLimit").rest()).isTrue();
    assertThat(cases.get("restingBuyQuoteCrossFillsAtLimit").legs()).singleElement().satisfies(l -> {
      assertThat(l.price()).isEqualByComparingTo("95.00");
      assertThat(l.qty()).isEqualByComparingTo("100");             // max(size 20, 100)
      assertThat(l.liquidity()).isEqualTo(Liquidity.RESTING);
    });
    assertThat(cases.get("restingSellTradeThroughPrintsOnly").legs()).singleElement().satisfies(l -> {
      assertThat(l.qty()).isEqualByComparingTo("55");              // 30 + 25; the print at L is ignored
      assertThat(l.liquidity()).isEqualTo(Liquidity.PRINT);
    });
    assertThat(cases.get("restingRequiresPrintIgnoresTheQuote").legs()).isEmpty();
  }
}
