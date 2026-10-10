package com.stocks.contract.push.dto;

/** Phase 2 §18.3 {@code PushTestResult} ({@code POST /me/push/test}, §19.2): what happened to the test push. */
public record PushTestResultDto(int devices, int delivered, int failed, int removed) {}
