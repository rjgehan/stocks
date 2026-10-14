package com.stocks.contract.manualbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * {@code POST …/manual-sessions/{sessionId}/execute} (§20.8): the {@code index} of every ticked row; an empty list
 * only posts the summary to the journal.
 */
public record ManualExecuteRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Integer> actions) {}
