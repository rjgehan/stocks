package com.stocks.contract.manualbot.dto;

/** §20.8 console limits; {@code expireAfter} is an ISO-8601 duration ({@code "PT24H"}). */
public record ManualLimitsDto(int maxInstructionsChars, int maxReplyChars, int maxOrders, String expireAfter) {}
