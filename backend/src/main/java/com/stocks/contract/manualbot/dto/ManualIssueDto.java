package com.stocks.contract.manualbot.dto;

/**
 * One problem with a reply or a row (§20.8); code is one of §20.8's issue codes or an ErrorCode name from the
 * preview. {@code field} is the key as written in the reply; {@code line} and {@code column} are 1-based in the
 * normalized reply.
 */
public record ManualIssueDto(ManualIssueSeverity severity, String code, String field, Integer line, Integer column,
                             String message, String hint) {}
