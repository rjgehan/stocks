package com.stocks.contract.marketdata.dto;

import java.util.List;

/** {@code GET /news} and MCP {@code get_news} body (§18.2, §20.3): {@code {items: NewsItem[]}} (empty if no source). */
public record NewsListDto(List<NewsItemDto> items) {}
