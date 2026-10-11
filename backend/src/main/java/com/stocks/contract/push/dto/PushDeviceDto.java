package com.stocks.contract.push.dto;

import java.time.Instant;

/**
 * Phase 2 §18.3 {@code PushDevice} (§19.2): one browser subscription of the player. The endpoint and keys never leave
 * the server; {@code pushService} is the push service host, {@code current} marks the device registered by the
 * caller's session.
 */
public record PushDeviceDto(String id, String label, String pushService, boolean current, Instant createdAt,
                            Instant lastSuccessAt, Instant lastFailureAt) {}
