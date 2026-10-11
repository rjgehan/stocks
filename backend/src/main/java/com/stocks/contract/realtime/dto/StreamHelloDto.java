package com.stocks.contract.realtime.dto;

import java.util.List;

/**
 * The first event of an SSE stream (§19.3, {@code event: hello}): what the stream carries, whether a
 * {@code Last-Event-ID} was honoured ({@code resumed}) and how many buffered events were replayed.
 */
public record StreamHelloDto(String connectionId, List<StreamTopic> topics, List<String> symbols, boolean resumed,
                             int replayed, int heartbeatSeconds, int maxAgeSeconds) {}
