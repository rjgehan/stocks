package com.stocks.contract.marketdata.dto;

import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.TapePrint;
import com.stocks.contract.marketdata.TapeSide;
import com.stocks.contract.marketdata.TapeSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * §15.10 / §18.3 {@code TimeAndSales}: a symbol's recent prints, newest first ({@code GET /assets/{symbol}/trades}),
 * and the §19 {@code /topic/trades.{SYM}} payload (only the prints that are new since the previous message).
 * {@code streaming}: a trade stream covers the symbol now; {@code source} NONE means no prints can arrive. Prices are
 * at their traded scale (never rounded), sizes in shares.
 */
public record TimeAndSalesDto(String symbol, TapeSource source, boolean streaming, Instant asOf,
                              List<TapePrintDto> prints) {

  /** One print: trade time, price (raw, as traded), size in shares, side vs the quote, venue. */
  public record TapePrintDto(String id, Instant t, BigDecimal price, long size, TapeSide side, String venue) {

    public static TapePrintDto of(TapePrint p) {
      return new TapePrintDto(p.id(), p.ts(), p.price(), p.size(), p.side(), p.venue());
    }
  }

  /** The mapping every channel uses (REST, STOMP): {@code prints} may be a subset of the tape's (new ones only). */
  public static TimeAndSalesDto of(Tape tape, List<TapePrint> prints, Instant now) {
    return new TimeAndSalesDto(tape.symbol(), tape.source(), tape.streaming(), now,
        prints.stream().map(TapePrintDto::of).toList());
  }

  public static TimeAndSalesDto of(Tape tape, Instant now) {
    return of(tape, tape.prints(), now);
  }
}
