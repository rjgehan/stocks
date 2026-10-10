/**
 * Market-data model (§15.2, WS2): internal records returned by {@code MarketDataService} and the provider SPI.
 * They are <b>never serialized to clients</b>; the wire shapes are the §18.3 DTOs in
 * {@code com.stocks.contract.marketdata.dto}, produced by WS2's mappers.
 */
package com.stocks.contract.marketdata.model;
