package com.stocks.contract.calendar;

/**
 * Application event (§5.3): the market session changed. Published after the engine applies a transition. It lives
 * here rather than in {@code common.events} because it carries {@link SessionState}, and {@code common} may not
 * depend on {@code contract} (§5.2 rule 8). Listeners hand off to their own executor and never block.
 */
public record SessionChanged(SessionState state) {}
