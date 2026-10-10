package com.stocks.marketdata.mapper;

import com.stocks.contract.marketdata.dto.FundamentalsDto;
import com.stocks.contract.marketdata.model.Fundamentals;
import org.springframework.stereotype.Component;

/** {@code model.Fundamentals → FundamentalsDto} (the 52-week fields move to {@code Asset}/{@code Snapshot}), §18.3. */
@Component
public class FundamentalsDtoMapper {

  public static FundamentalsDto toDto(Fundamentals f) {
    return f == null ? null : new FundamentalsDto(f.marketCap(), f.peTtm(), f.epsTtm(), f.dividendYield(), f.beta(),
        f.sharesOutstanding(), f.industry(), f.website(), f.source());
  }
}
