package com.stocks.contract.marketdata;

import com.stocks.contract.marketdata.model.OptionQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §30: every contract of one expiration of {@code underlying}, calls and puts by ascending strike, with the
 * underlying's live mark and the option feed ({@code indicative}, {@code opra} or {@code simulated}).
 */
public record OptionChain(String underlying, LocalDate expiration, BigDecimal underlyingPrice, String feed,
                          Instant asOf, List<OptionQuote> contracts) {

  public OptionChain {
    contracts = contracts == null ? List.of() : List.copyOf(contracts);
  }
}
