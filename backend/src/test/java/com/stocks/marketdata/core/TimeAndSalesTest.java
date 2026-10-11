package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.TapePrint;
import com.stocks.contract.marketdata.TapeSide;
import com.stocks.contract.marketdata.TapeSource;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.QuoteEvent;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradeEvent;
import com.stocks.marketdata.config.ExecQuoteRules;
import com.stocks.marketdata.sim.SimScenarios;
import com.stocks.marketdata.sim.SimulatedMarketDataProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * §15.10 Time &amp; Sales: prints are colored against the quote in force, only stream-delivered prints reach the tape
 * (never the poller's sampled latest trade), IEX prints are dropped while SIP prints flow, and the SIM's tape is its
 * own prints up to now.
 */
class TimeAndSalesTest {

  static final Instant T = Instant.parse("2026-10-09T14:30:00Z");
  static final MarketCalendar CAL = InMemoryCalendars.bundled();

  static Quote quote(String bid, String ask, Instant ts) {
    return new Quote("AAPL", new BigDecimal(bid), 300, new BigDecimal(ask), 200, "V", "V", ts, ts,
        DataQuality.REALTIME_SINGLE_VENUE, "alpaca");
  }

  static Trade trade(String price, long size, Instant ts, boolean consolidated, String id) {
    return new Trade("AAPL", new BigDecimal(price), size, ts, ts, consolidated ? "SIP" : "V", List.of(), consolidated,
        true, id, consolidated ? "finnhub" : "alpaca");
  }

  @Test
  void classifierColorsPrintsAgainstTheQuote() {
    BigDecimal bid = new BigDecimal("227.39");
    BigDecimal ask = new BigDecimal("227.41");
    assertThat(TapeClassifier.classify(new BigDecimal("227.41"), bid, ask)).isEqualTo(TapeSide.AT_ASK);
    assertThat(TapeClassifier.classify(new BigDecimal("227.45"), bid, ask)).as("above the ask").isEqualTo(TapeSide.AT_ASK);
    assertThat(TapeClassifier.classify(new BigDecimal("227.39"), bid, ask)).isEqualTo(TapeSide.AT_BID);
    assertThat(TapeClassifier.classify(new BigDecimal("227.30"), bid, ask)).as("below the bid").isEqualTo(TapeSide.AT_BID);
    assertThat(TapeClassifier.classify(new BigDecimal("227.40"), bid, ask)).isEqualTo(TapeSide.BETWEEN);
    assertThat(TapeClassifier.classify(new BigDecimal("227.40"), null, ask)).isEqualTo(TapeSide.UNKNOWN);
    assertThat(TapeClassifier.classify(new BigDecimal("227.40"), BigDecimal.ZERO, ask)).as("one-sided")
        .isEqualTo(TapeSide.UNKNOWN);
    assertThat(TapeClassifier.classify(new BigDecimal("227.40"), ask, bid)).as("crossed").isEqualTo(TapeSide.UNKNOWN);
    assertThat(TapeClassifier.classify(new BigDecimal("227.40"), new BigDecimal("227.40"), new BigDecimal("227.40")))
        .as("locked: at the ask").isEqualTo(TapeSide.AT_ASK);
  }

  @Test
  void streamPrintsReachTheTapeNewestFirstColoredByTheQuoteInForce() {
    MutableTimeSource time = new MutableTimeSource(T);
    QuoteBook book = new QuoteBook(time, List::of);
    book.onEvent(new QuoteEvent(quote("227.39", "227.41", T)));
    book.onEvent(new TradeEvent(trade("227.41", 100, T.plusMillis(10), false, "1")));
    book.onEvent(new TradeEvent(trade("227.39", 50, T.plusMillis(20), false, "2")));
    book.onEvent(new QuoteEvent(quote("227.50", "227.52", T.plusMillis(30))));
    book.onEvent(new TradeEvent(trade("227.51", 200, T.plusMillis(40), false, "3")));
    book.onEvent(new TradeEvent(trade("227.41", 100, T.plusMillis(10), false, "1")));   // the same print again

    List<TapePrint> tape = book.tape("AAPL", 10);
    assertThat(tape).extracting(TapePrint::id).containsExactly("i3", "i2", "i1");
    assertThat(tape).extracting(TapePrint::side).containsExactly(TapeSide.BETWEEN, TapeSide.AT_BID, TapeSide.AT_ASK);
    assertThat(tape.get(0).price()).isEqualByComparingTo("227.51");
    assertThat(tape.get(0).size()).isEqualTo(200);
    assertThat(book.tape("AAPL", 2)).extracting(TapePrint::id).containsExactly("i3", "i2");
    assertThat(book.tape("MSFT", 10)).isEmpty();
  }

  @Test
  void theSnapshotPollersLatestTradeIsNeverOnTheTape() {
    MutableTimeSource time = new MutableTimeSource(T);
    QuoteBook book = new QuoteBook(time, List::of);
    Trade sampled = trade("227.40", 100, T, false, "9");
    book.applySnapshot(new Snapshot("AAPL", sampled, quote("227.39", "227.41", T), null, null, null, T), T,
        LocalDate.of(2026, 10, 9), null);
    assertThat(book.view("AAPL").orElseThrow().lastTrade()).isEqualTo(sampled);
    assertThat(book.tape("AAPL", 10)).as("a sampled print would make the tape a lie").isEmpty();
  }

  @Test
  void iexPrintsAreDroppedWhileConsolidatedPrintsFlow() {
    MutableTimeSource time = new MutableTimeSource(T);
    QuoteBook book = new QuoteBook(time, List::of);
    book.onEvent(new QuoteEvent(quote("227.39", "227.41", T)));
    book.onEvent(new TradeEvent(trade("227.40", 100, T, false, "a")));            // IEX before the SIP stream
    time.advance(Duration.ofSeconds(1));
    book.onEvent(new TradeEvent(trade("227.41", 300, T.plusSeconds(1), true, null)));
    book.onEvent(new TradeEvent(trade("227.41", 100, T.plusSeconds(1), false, "b")));   // the same print via IEX
    time.advance(Duration.ofMinutes(2));                                             // the SIP stream went quiet
    book.onEvent(new TradeEvent(trade("227.42", 10, T.plusSeconds(121), false, "c")));

    List<TapePrint> tape = book.tape("AAPL", 10);
    assertThat(tape).extracting(TapePrint::consolidated).containsExactly(false, true, false);
    assertThat(tape).extracting(TapePrint::id).first().isEqualTo("ic");
    assertThat(tape.get(1).id()).as("a SIP print without a provider id").startsWith("c").contains("227.41x300");
  }

  @Test
  void theTapeIsBounded() {
    MutableTimeSource time = new MutableTimeSource(T);
    QuoteBook book = new QuoteBook(time, List::of);
    for (int i = 0; i < QuoteBook.TAPE_SIZE + 50; i++) {
      book.onEvent(new TradeEvent(trade("227.40", 1 + i, T.plusMillis(i), false, "n" + i)));
    }
    List<TapePrint> all = book.tape("AAPL", 10_000);
    assertThat(all).hasSize(QuoteBook.TAPE_SIZE);
    assertThat(all.get(0).id()).isEqualTo("in" + (QuoteBook.TAPE_SIZE + 49));
  }

  @Test
  void theSimTapeIsItsPrintsUpToNowColoredAgainstItsQuotes() {
    MutableTimeSource time = new MutableTimeSource(T);
    SimulatedMarketDataProvider sim = new SimulatedMarketDataProvider(42, CAL, Universe.load(), SimScenarios.none(),
        time);
    SimFeed feed = new SimFeed(sim, sim.capabilities(), CAL, time, ExecQuoteRules.defaults(), s -> LiquidityTier.MEGA,
        new SubscriptionBudgetManager(time), List::of);

    Tape tape = feed.tape("AAPL", 40);
    assertThat(tape.source()).isEqualTo(TapeSource.SIMULATED);
    assertThat(tape.streaming()).isTrue();
    assertThat(tape.prints()).hasSize(40);
    assertThat(tape.prints()).isSortedAccordingTo((a, b) -> b.ts().compareTo(a.ts()));
    assertThat(tape.prints().get(0).ts()).isBeforeOrEqualTo(T);
    Set<String> ids = new HashSet<>();
    tape.prints().forEach(p -> assertThat(ids.add(p.id())).as("unique id " + p.id()).isTrue());
    // the SIM prints 45% at the bid, 45% at the ask and 10% inside (§15.7): every print is classified
    assertThat(tape.prints()).extracting(TapePrint::side).doesNotContain(TapeSide.UNKNOWN)
        .contains(TapeSide.AT_ASK, TapeSide.AT_BID);
    assertThat(tape.prints().get(0).id()).isEqualTo(sim.lastTradeAt("AAPL", T).orElseThrow().id());
    // deterministic: the same tape at the same instant
    assertThat(feed.tape("AAPL", 40)).isEqualTo(tape);
    assertThat(feed.tape("NOPE", 10)).isEqualTo(Tape.none("NOPE"));
  }

  @Test
  void preMarketSimPrintsAreSparseButPresent() {
    MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-09T12:00:00Z"));   // 08:00 ET, PRE
    SimulatedMarketDataProvider sim = new SimulatedMarketDataProvider(42, CAL, Universe.load(), SimScenarios.none(),
        time);
    SimFeed feed = new SimFeed(sim, sim.capabilities(), CAL, time, ExecQuoteRules.defaults(), s -> LiquidityTier.SMALL,
        new SubscriptionBudgetManager(time), List::of);
    Tape tape = feed.tape("SIMA", 5);
    assertThat(tape.prints()).isNotEmpty().allSatisfy(p -> assertThat(p.ts()).isBefore(time.now().plusNanos(1)));
  }
}
