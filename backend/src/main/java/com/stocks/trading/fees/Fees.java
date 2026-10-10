package com.stocks.trading.fees;

import com.stocks.common.money.Money;
import com.stocks.contract.trading.dto.FeesDto;
import java.math.BigDecimal;

/** Regulatory fees of one execution or estimate (§13), each at scale 2. */
public record Fees(BigDecimal sec, BigDecimal taf, BigDecimal cat) {

  public static final Fees ZERO = new Fees(Money.ZERO, Money.ZERO, Money.ZERO);

  public BigDecimal total() {
    return sec.add(taf).add(cat);
  }

  public Fees plus(Fees other) {
    return new Fees(sec.add(other.sec), taf.add(other.taf), cat.add(other.cat));
  }

  public FeesDto toDto() {
    return new FeesDto(sec, taf, cat, total());
  }
}
