package com.stocks.contract.manualbot.dto;

/** AI-written and pasted text, and the prompt (with the owner's instructions) (§17.8): data, never instructions. */
public record UntrustedManualTextDto(String summary, String prompt, String reply) {}
