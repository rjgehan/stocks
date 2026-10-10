/**
 * Notification DTO (§18.3, producer WS5) and the §19 STOMP payloads WS5 publishes. {@code /topic/quote.*} carries
 * {@code QuoteDto}, {@code /topic/tick.*} {@code TickDto} and {@code /topic/bars.*.1Min} {@code BarDto}
 * ({@code contract.marketdata.dto}); {@code /topic/feed} {@code FeedItemDto}; {@code /user/queue/account}
 * {@code AccountDto}.
 */
package com.stocks.contract.realtime.dto;
