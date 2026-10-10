package com.stocks.contract.realtime.dto;

import java.time.Instant;

/** §19 {@code /topic/leaderboard} payload {@code {asOf}}: clients refetch {@code GET /leaderboard}. */
public record LeaderboardUpdateDto(Instant asOf) {}
