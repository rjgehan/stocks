package com.stocks.contract.marketdata.model;

import java.time.Instant;
import java.util.List;

/** A news article. Headline and summary are untrusted text (§17.8); the DTO wraps them in {@code untrusted}. */
public record NewsItem(String id, String headline, String summary, String url, String source, List<String> symbols,
                       Instant publishedAt, String imageUrl) {}
