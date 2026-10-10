package com.stocks.contract.marketdata.model;

public record QuoteEvent(Quote quote) implements MarketEvent {}
