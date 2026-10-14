package com.stocks.contract.agents.dto;

/**
 * §20.9 "Run all": what {@code POST /me/bots/run-requests} did for one bot. {@code REQUESTED}: a new PENDING request;
 * {@code ALREADY_OPEN}: the bot already had one waiting or running (that one is returned); {@code SKIPPED}: nothing
 * was requested, see {@link RunAllSkipReason}.
 */
public enum RunAllOutcome {
  REQUESTED, ALREADY_OPEN, SKIPPED
}
