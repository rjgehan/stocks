package com.stocks.contract.manualbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST …/manual-sessions/{sessionId}/reply} (§20.8): the AI's whole reply, at most 20,000 characters. */
public record ManualReplyRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reply) {}
