package com.stocks.contract.realtime;

import com.stocks.contract.realtime.dto.NotificationDto;

/**
 * Phase 2 (§19.2): {@code NotificationService} wrote a new notification (not a redelivered one) for
 * {@code playerId}. Published synchronously on the writer's thread right after the insert; listeners only enqueue
 * (Web Push, §19.2) and never block.
 */
public record NotificationCreated(long playerId, NotificationDto notification) {}
