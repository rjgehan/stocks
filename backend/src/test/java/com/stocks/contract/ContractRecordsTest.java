package com.stocks.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.marketdata.dto.BarDto;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.SnapshotDto;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Behaviour carried by the §5.3 / §15.2 records. */
class ContractRecordsTest {

  static final Instant T = Instant.parse("2026-10-09T14:30:00Z");

  @Test
  void execQuoteSpreadAndMid() {
    ExecQuote q = new ExecQuote("AAPL", new BigDecimal("227.40"), 100, new BigDecimal("227.43"), 200,
        new BigDecimal("227.41"), T, T, DataQuality.REALTIME_SINGLE_VENUE, "alpaca", false);
    assertThat(q.spread()).isEqualByComparingTo("0.03");
    assertThat(q.mid()).isEqualTo(new BigDecimal("227.415000"));
  }

  @Test
  void quoteTwoSidedIsNullSafe() {
    assertThat(new Quote("A", new BigDecimal("1.00"), 1, new BigDecimal("1.01"), 1, "V", "V", T, T,
        DataQuality.SIMULATED, "sim").twoSided()).isTrue();
    assertThat(new Quote("A", new BigDecimal("1.02"), 1, new BigDecimal("1.01"), 1, "V", "V", T, T,
        DataQuality.SIMULATED, "sim").twoSided()).isFalse();
    assertThat(new Quote("A", null, 0, new BigDecimal("1.01"), 1, null, "V", T, T,
        DataQuality.SIMULATED, "sim").twoSided()).isFalse();
  }

  @Test
  void quoteViewNoneKnowsNothing() {
    QuoteView v = QuoteView.none("ZZZZ", "none");
    assertThat(v.quality()).isEqualTo(DataQuality.NONE);
    assertThat(v.bid()).isNull();
    assertThat(v.halted()).isFalse();
  }

  @Test
  void snapshotStartsWithEveryQuoteMemberInOrder() {
    String[] quote = Arrays.stream(QuoteDto.class.getRecordComponents()).map(RecordComponent::getName)
        .toArray(String[]::new);
    String[] snap = Arrays.stream(SnapshotDto.class.getRecordComponents()).map(RecordComponent::getName)
        .toArray(String[]::new);
    assertThat(Arrays.copyOf(snap, quote.length)).containsExactly(quote);
    QuoteDto q = (QuoteDto) DtoSamples.sample(QuoteDto.class);
    BarDto bar = (BarDto) DtoSamples.sample(BarDto.class);
    SnapshotDto s = SnapshotDto.of(q, bar, null, BigDecimal.ONE, BigDecimal.TEN, null);
    assertThat(s.symbol()).isEqualTo(q.symbol());
    assertThat(s.session()).isEqualTo(q.session());
    assertThat(s.minuteBar()).isEqualTo(bar);
  }

  @Test
  void zeroFees() {
    assertThat(FeesDto.zero().total()).isEqualTo(new BigDecimal("0.00"));
  }
}
