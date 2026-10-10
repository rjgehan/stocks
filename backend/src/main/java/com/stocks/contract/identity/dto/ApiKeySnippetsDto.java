package com.stocks.contract.identity.dto;

/** §18.3 {@code ApiKeyCreated.snippets}: copy-once connection recipes (§20.4). */
public record ApiKeySnippetsDto(String claudeCode, String claudeApi, String openaiResponses, String curl) {}
