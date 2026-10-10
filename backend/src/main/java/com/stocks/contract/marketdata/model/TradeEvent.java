package com.stocks.contract.marketdata.model;

public record TradeEvent(Trade trade) implements MarketEvent {}
