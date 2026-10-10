package com.stocks.contract.marketdata.dto;

import java.time.Instant;
import java.util.List;

/** §18.3 {@code NewsItem}; headline and summary are third-party text, wrapped in {@code untrusted} (§17.8). */
public record NewsItemDto(String id, Instant publishedAt, String source, String url, List<String> symbols,
                          String imageUrl, UntrustedNewsDto untrusted) {

  public record UntrustedNewsDto(String headline, String summary) {}
}
