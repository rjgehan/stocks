package com.stocks.contract.agents.dto;

/**
 * §20.9 "Run all": why a bot got no request. {@code NOT_ACTIVE}: it is FROZEN, so it cannot trade;
 * {@code NO_TRADER}: no claude-trader service has asked for its requests in the last 5 minutes (it is not in the
 * trader's agents file, e.g. a bot of the agent runner or a manual bot, or the service is down), so nothing would pick
 * one up; {@code RATE_LIMITED}: the bot used its 20 requests of the last 24 hours.
 */
public enum RunAllSkipReason {
  NOT_ACTIVE, NO_TRADER, RATE_LIMITED
}
