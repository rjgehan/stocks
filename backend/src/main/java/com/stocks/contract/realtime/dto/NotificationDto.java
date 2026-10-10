package com.stocks.contract.realtime.dto;

import com.stocks.common.domain.NotificationKind;
import java.time.Instant;
import java.util.Map;

/** §18.3 {@code Notification} ({@code /me/notifications}, {@code /user/queue/notifications}). */
public record NotificationDto(String id, NotificationKind kind, String title, String body, Map<String, Object> payload,
                              Instant createdAt, Instant readAt) {}
