package com.stocks.contract.jobs;

import java.time.Instant;

/** One planned run of a {@link ScheduledJob}: when, and the {@code job_run.run_key} that makes it idempotent (§25). */
public record JobOccurrence(Instant at, String runKey) {}
