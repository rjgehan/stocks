package com.stocks.contract.marketdata.dto;

import java.util.List;

/** {@code GET /quotes} and MCP {@code get_quotes} body (§18.2, §20.3): {@code {quotes: Quote[]}}. */
public record QuotesDto(List<QuoteDto> quotes) {}
