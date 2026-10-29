package com.stocks.marketdata.core;

import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.IndicatorsDto;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Exact daily indicators of a symbol ({@code GET /market/indicators}, MCP {@code get_indicators}): about 300 daily
 * bars (split and dividend adjusted) plus the live mark as today's close, through {@link Indicators}.
 *
 * <p>Today's close: when the newest daily bar started less than 24 hours ago it is today's (partial) bar and the live
 * price replaces its close; otherwise the live price is appended as a new close only if it traded after that bar's
 * day (so a weekend or holiday never counts the last close twice).
 */
@Service
public class IndicatorService {

  static final int SESSIONS = 300;
  static final Duration LOOKBACK = Duration.ofDays(460);
  static final Duration DAY = Duration.ofHours(24);

  private final MarketDataService marketData;
  private final TimeSource time;

  public IndicatorService(MarketDataService marketData, TimeSource time) {
    this.marketData = marketData;
    this.time = time;
  }

  public IndicatorsDto indicators(String symbol) {
    if (marketData.asset(symbol).isEmpty()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol " + symbol + ".")
          .withHint("Use search_symbols to find the ticker.");
    }
    Instant now = time.now();
    BarSeries series = marketData.bars(new BarRequest(symbol, null, Timeframe.DAY1, now.minus(LOOKBACK), now,
        SESSIONS, AdjustmentMode.ALL, false));
    List<Bar> bars = series.bars() == null ? List.of() : series.bars();
    List<BigDecimal> closes = new ArrayList<>();
    for (Bar b : bars) {
      if (b.close() != null) {
        closes.add(b.close());
      }
    }
    Mark mark = marketData.mark(symbol);
    BigDecimal price = mark == null ? null : mark.price();
    boolean today = false;
    if (price != null && price.signum() > 0 && !bars.isEmpty()) {
      Instant lastStart = bars.get(bars.size() - 1).start();
      if (lastStart.isAfter(now.minus(DAY))) {
        closes.set(closes.size() - 1, price);
        today = true;
      } else if (mark.asOf() != null && mark.asOf().isAfter(lastStart.plus(DAY))) {
        closes.add(price);
        today = true;
      }
    }
    if (closes.isEmpty()) {
      return new IndicatorsDto(symbol, now, price, false, 0, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null);
    }
    BigDecimal last = closes.get(closes.size() - 1);
    BigDecimal sma50 = Indicators.sma(closes, 50);
    BigDecimal sma200 = Indicators.sma(closes, 200);
    return new IndicatorsDto(symbol, now, today ? price : last, today, closes.size(), Indicators.sma(closes, 5),
        Indicators.sma(closes, 20), sma50, Indicators.sma(closes, 100), sma200,
        Indicators.ratioMinusOne(last, sma200), Indicators.ratioMinusOne(last, sma50), Indicators.rsi(closes, 2),
        Indicators.rsi(closes, 14), Indicators.ret(closes, 21), Indicators.ret(closes, 63), Indicators.ret(closes, 126),
        Indicators.extreme(closes, 252, true), Indicators.extreme(closes, 252, false));
  }
}
