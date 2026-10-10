package com.stocks.contract.marketdata.model;

import java.time.LocalDate;
import java.time.LocalTime;

/** A provider calendar row (ET wall-clock times), before the calendar converts it to a {@code TradingDay}. */
public record TradingDayData(LocalDate date, LocalTime preOpen, LocalTime open, LocalTime close, LocalTime postClose,
                             LocalDate settlementDate) {}
