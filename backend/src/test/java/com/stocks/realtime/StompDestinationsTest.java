package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.realtime.StompDestinations.Kind;
import com.stocks.realtime.StompDestinations.Parsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** §19 destinations: what a client may subscribe to, and the lease each market topic holds. */
class StompDestinationsTest {

  @Test
  void marketTopicsCarryTheirSymbolAndLease() {
    Parsed quote = StompDestinations.parse("/topic/quote.AAPL").orElseThrow();
    assertThat(quote.kind()).isEqualTo(Kind.QUOTE);
    assertThat(quote.symbol()).isEqualTo("AAPL");
    assertThat(quote.kind().leasePriority()).isEqualTo(LeasePriority.VIEWING);

    Parsed tick = StompDestinations.parse("/topic/tick.BRK.B").orElseThrow();
    assertThat(tick.kind()).isEqualTo(Kind.TICK);
    assertThat(tick.symbol()).isEqualTo("BRK.B");
    assertThat(tick.kind().leasePriority()).isEqualTo(LeasePriority.LIST);

    Parsed bars = StompDestinations.parse("/topic/bars.SPY.1Min").orElseThrow();
    assertThat(bars.kind()).isEqualTo(Kind.BARS);
    assertThat(bars.symbol()).isEqualTo("SPY");
    assertThat(bars.kind().leasePriority()).isEqualTo(LeasePriority.VIEWING);
    assertThat(bars.kind().isMarket()).isTrue();
  }

  @Test
  void broadcastTopicsAndUserQueues() {
    assertThat(StompDestinations.parse("/topic/market.session").orElseThrow().kind()).isEqualTo(Kind.SESSION);
    assertThat(StompDestinations.parse("/topic/leaderboard").orElseThrow().kind()).isEqualTo(Kind.LEADERBOARD);
    assertThat(StompDestinations.parse("/topic/feed").orElseThrow().kind()).isEqualTo(Kind.FEED);
    for (String q : new String[] {"/user/queue/orders", "/user/queue/account", "/user/queue/notifications"}) {
      Parsed p = StompDestinations.parse(q).orElseThrow();
      assertThat(p.kind()).isEqualTo(Kind.USER_QUEUE);
      assertThat(p.kind().isMarket()).isFalse();
      assertThat(p.kind().leasePriority()).isNull();
    }
    assertThat(StompDestinations.parse("/user/queue/admin").orElseThrow().kind()).isEqualTo(Kind.ADMIN_QUEUE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/queue/orders", "/queue/orders-user123", "/topic/other", "/user/queue/secrets",
      "/user/ply_01/queue/orders", "/app/x", "/topic/bars.AAPL.5Min", "", "/topic/quote"})
  void everythingElseIsUnknown(String destination) {
    assertThat(StompDestinations.parse(destination)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/topic/quote.aapl", "/topic/quote.", "/topic/tick.1ABC", "/topic/quote.TOOLONGSYMBOL1",
      "/topic/tick.A$B", "/topic/bars.aapl.1Min"})
  void nonCanonicalSymbolsAreMarketTopicsWithAnInvalidSymbol(String destination) {
    Parsed p = StompDestinations.parse(destination).orElseThrow();
    assertThat(p.kind().isMarket()).isTrue();
    assertThat(StompDestinations.invalidSymbol(p)).isTrue();
  }

  @Test
  void buildersMatchTheParser() {
    assertThat(StompDestinations.quote("NVDA")).isEqualTo("/topic/quote.NVDA");
    assertThat(StompDestinations.tick("NVDA")).isEqualTo("/topic/tick.NVDA");
    assertThat(StompDestinations.bars("NVDA")).isEqualTo("/topic/bars.NVDA.1Min");
    assertThat(StompDestinations.parse(StompDestinations.bars("NVDA")).orElseThrow().symbol()).isEqualTo("NVDA");
  }

  @Test
  void parseOfNullIsEmpty() {
    assertThat(StompDestinations.parse(null)).isEmpty();
  }
}
