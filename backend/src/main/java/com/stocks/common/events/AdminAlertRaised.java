package com.stocks.common.events;

import java.time.Instant;

/**
 * Something needs the admin's attention (§19 {@code /user/queue/admin}), e.g. a corporate action that needs review
 * (§14). WS5 pushes it to every connected admin as {@code AdminAlertDto}. {@code level}: {@code INFO}, {@code WARN} or
 * {@code ERROR}.
 */
public record AdminAlertRaised(String level, String title, String body, Instant at) {}
