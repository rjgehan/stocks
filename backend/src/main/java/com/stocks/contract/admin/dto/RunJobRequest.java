package com.stocks.contract.admin.dto;

/** {@code POST /admin/system/jobs/{jobName}/run} (§21.1). {@code runKey} null = the latest past occurrence. */
public record RunJobRequest(String runKey) {}
