package com.stocks.contract.marketdata.model;

/** A message from an upstream stream ({@code MarketStream}), delivered to the provider's sink. */
public sealed interface MarketEvent permits TradeEvent, QuoteEvent, BarEvent, StatusEvent, NewsEvent {}
