/**
 * Application events (§5.3, CONTRACT). The engine publishes them through {@code ApplicationEventPublisher} <b>after
 * commit</b>, on the engine thread; {@link com.stocks.common.events.PlayerStatusChanged} is published by WS1's
 * {@code AdminPlayerService} after its own transaction (§10.5). Listeners MUST hand off to their own executor
 * ({@code @Async("eventsExecutor")}) and never block. Delivery is best-effort, so every listener that writes rows is
 * idempotent through a {@code source_ref} key (§12.8).
 *
 * <p>{@code SessionChanged(SessionState state)} lives in {@code com.stocks.contract.calendar}, next to
 * {@code SessionState}: {@code common} may not depend on {@code contract} (§5.2 rules 7 and 8).
 */
package com.stocks.common.events;
