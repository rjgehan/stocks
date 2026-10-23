package com.stocks.marketdata.mapper;

import com.stocks.common.domain.OptionRight;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.contract.marketdata.OptionChain;
import com.stocks.contract.marketdata.OptionExpirations;
import com.stocks.contract.marketdata.dto.OptionChainDto;
import com.stocks.contract.marketdata.dto.OptionContractDto;
import com.stocks.contract.marketdata.dto.OptionExpirationsDto;
import com.stocks.contract.marketdata.model.OptionQuote;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.marketdata.core.MarkService;
import java.math.BigDecimal;
import java.util.List;

/** §30 option chain DTOs: positive prices only (a zero side is "none"), the mark as {@code mid}. */
public final class OptionDtoMapper {

  private OptionDtoMapper() {}

  public static OptionExpirationsDto toDto(OptionExpirations e, boolean enabled) {
    return new OptionExpirationsDto(e.underlying(), enabled, e.feed(), OptionSymbol.MULTIPLIER, e.expirations());
  }

  public static OptionChainDto toDto(OptionChain c, boolean enabled) {
    List<OptionContractDto> calls = c.contracts().stream().filter(q -> q.right() == OptionRight.CALL)
        .map(OptionDtoMapper::toDto).toList();
    List<OptionContractDto> puts = c.contracts().stream().filter(q -> q.right() == OptionRight.PUT)
        .map(OptionDtoMapper::toDto).toList();
    return new OptionChainDto(c.underlying(), c.expiration(), enabled, c.feed(), OptionSymbol.MULTIPLIER,
        c.underlyingPrice(), c.asOf(), calls, puts);
  }

  public static OptionContractDto toDto(OptionQuote o) {
    Quote q = o.quote();
    BigDecimal bid = q == null ? null : positive(q.bid());
    BigDecimal ask = q == null ? null : positive(q.ask());
    return new OptionContractDto(o.symbol(), o.underlying(), o.expiration(), o.right(), o.strike(), bid,
        bid == null ? null : q.bidSize(), ask, ask == null ? null : q.askSize(), MarkService.optionMid(q),
        o.lastTrade() == null ? null : o.lastTrade().price(), o.volume(), o.openInterest(), o.impliedVolatility(),
        o.delta(), q == null ? null : q.ts(), q == null ? null : q.quality());
  }

  private static BigDecimal positive(BigDecimal v) {
    return v == null || v.signum() <= 0 ? null : v;
  }
}
