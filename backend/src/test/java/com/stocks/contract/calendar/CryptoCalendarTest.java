package com.stocks.contract.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.trading.testkit.TestCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** §29: the 24/7 crypto calendar and the crypto symbol rules. */
class CryptoCalendarTest {

  static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
  static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);

  @Test
  void everyInstantIsRegularAndEveryDaySettlesOnItself() {
    CryptoCalendar c = CryptoCalendar.INSTANCE;
    for (LocalTime t : new LocalTime[] {LocalTime.MIDNIGHT, LocalTime.of(3, 59), LocalTime.of(12, 0),
        LocalTime.of(23, 59, 59)}) {
      Instant at = Et.at(SATURDAY, t);
      SessionState s = c.stateAt(at);
      assertThat(s.type()).isEqualTo(SessionType.REGULAR);
      assertThat(s.tradingDate()).isEqualTo(SATURDAY);
      assertThat(s.endsAt()).isEqualTo(Et.startOfDay(SATURDAY.plusDays(1)));
    }
    assertThat(c.isTradingDay(THANKSGIVING)).isTrue();
    assertThat(c.settlementDate(SATURDAY)).isEqualTo(SATURDAY);
    assertThat(c.previousTradingDay(SATURDAY)).isEqualTo(SATURDAY.minusDays(1));
    assertThat(c.currentPnlDay(Et.at(SATURDAY, LocalTime.of(1, 0)))).isEqualTo(SATURDAY);
    assertThat(c.transitionsBetween(Et.startOfDay(SATURDAY), Et.startOfDay(SATURDAY.plusDays(7)))).isEmpty();
    TradingDay d = c.day(SATURDAY).orElseThrow();
    assertThat(d.rthOpenAt()).isEqualTo(Et.startOfDay(SATURDAY));
    assertThat(d.rthCloseAt()).isEqualTo(d.postCloseAt()).isEqualTo(Et.startOfDay(SATURDAY.plusDays(1)));
  }

  @Test
  void forSymbolPicksTheCryptoCalendarOnlyForPairs() {
    MarketCalendar nyse = new TestCalendar();
    assertThat(nyse.forSymbol("BTC/USD")).isSameAs(CryptoCalendar.INSTANCE);
    assertThat(nyse.forSymbol("AAPL")).isSameAs(nyse);
    assertThat(nyse.forSymbol("BRK.B")).isSameAs(nyse);
    assertThat(nyse.forSymbol(null)).isSameAs(nyse);
    assertThat(nyse.stateAt(Et.at(SATURDAY, LocalTime.NOON)).type()).isEqualTo(SessionType.CLOSED);
  }

  @Test
  void symbolsAndPaths() {
    assertThat(Crypto.isCrypto("BTC/USD")).isTrue();
    assertThat(Crypto.isCrypto("BRK.B")).isFalse();
    assertThat(Crypto.isCrypto("/USD")).isFalse();
    assertThat(Crypto.fromPath("btc-usd")).isEqualTo("BTC/USD");
    assertThat(Crypto.fromPath("ETHUSD")).isEqualTo("ETH/USD");
    assertThat(Crypto.fromPath("BTC/USD")).isEqualTo("BTC/USD");
    assertThat(Crypto.fromPath("brk.b")).isEqualTo("BRK.B");
    assertThat(Crypto.fromPath("FOO-USD")).isEqualTo("FOO-USD");          // not a seeded pair: unchanged
    assertThat(Crypto.toPath("BTC/USD")).isEqualTo("BTC-USD");
    assertThat(Crypto.toPath("AAPL")).isEqualTo("AAPL");
    assertThat(Crypto.symbols()).hasSize(12).contains("BTC/USD", "ETH/USD", "DOGE/USD");
    Pattern p = Pattern.compile(Crypto.SYMBOL_REGEX);
    assertThat(p.matcher("BTC/USD").matches()).isTrue();
    assertThat(p.matcher("BRK.B").matches()).isTrue();
    assertThat(p.matcher("BTC/").matches()).isFalse();
    assertThat(p.matcher("btc/usd").matches()).isFalse();
  }
}
