/**
 * §18.3 market DTOs (producer WS2; one mapper per DTO in {@code marketdata.mapper}, reused by REST, STOMP and MCP):
 * {@code Clock}, {@code TradingDay}, {@code AssetSummary}, {@code Asset}, {@code Fundamentals}, {@code ExtendedLine},
 * {@code Quote}, {@code Bar}, {@code Snapshot}, {@code Bars}, {@code Movers}, {@code NewsItem}, the
 * {@code {quotes}}/{@code {items}} list bodies and the §19 {@code /topic/tick.*} payload.
 */
package com.stocks.contract.marketdata.dto;
