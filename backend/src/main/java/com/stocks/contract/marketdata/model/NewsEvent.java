package com.stocks.contract.marketdata.model;

public record NewsEvent(NewsItem item) implements MarketEvent {}
