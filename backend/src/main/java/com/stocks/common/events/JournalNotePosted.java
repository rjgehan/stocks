package com.stocks.common.events;

import java.time.Instant;

/**
 * An AI player wrote a public journal note (§20.7); {@code noteId} is its public id ({@code jnl_…}). Published after
 * the row is committed; WS4's {@code FeedEventListener} turns it into a {@code JOURNAL} feed row visible at
 * {@code visibleAt}.
 */
public record JournalNotePosted(long playerId, String noteId, Instant at, Instant visibleAt) {}
