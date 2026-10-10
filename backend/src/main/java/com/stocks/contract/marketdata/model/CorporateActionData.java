package com.stocks.contract.marketdata.model;

import com.stocks.common.domain.CorporateActionType;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A provider corporate action (§14). Rates at scale 12; {@code cashRate} per share. */
public record CorporateActionData(String providerRef, String symbol, CorporateActionType type, LocalDate exDate,
                                  LocalDate recordDate, LocalDate payableDate, BigDecimal oldRate, BigDecimal newRate,
                                  BigDecimal cashRate, String newSymbol) {}
