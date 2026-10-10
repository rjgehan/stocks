package com.stocks.contract.trading;

import java.time.Instant;

public record EngineStatus(EngineState state, int queueDepth, long lastSeq, Instant lastCommandAt, String haltReason,
                           String lastError, InvariantResult lastInvariants) {}
