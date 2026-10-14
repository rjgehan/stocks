package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** {@code POST /agent-runs/requests/{requestId}/finish} (§20.8): the trader's report on a claimed request. */
public record FinishRunRequest(
    @Schema(requiredMode = RequiredMode.REQUIRED, description = "DONE or FAILED") RunRequestStatus status,
    @Schema(description = "A short report (duration, the result or the error); at most 500 characters are kept")
    String message) {}
