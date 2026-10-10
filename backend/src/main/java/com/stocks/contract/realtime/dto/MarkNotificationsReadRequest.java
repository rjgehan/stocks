package com.stocks.contract.realtime.dto;

import java.util.List;

/** {@code POST /me/notifications/read} (§18.1): exactly one of {@code ids} and {@code all=true}. */
public record MarkNotificationsReadRequest(List<String> ids, Boolean all) {}
