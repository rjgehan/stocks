package com.stocks.contract.marketdata.model;

/** {@code updated}: an {@code updatedBars} correction of an already emitted bar. */
public record BarEvent(Bar bar, boolean updated) implements MarketEvent {}
