package com.stocks.common.error;

/** One entry of a problem's {@code fields} array (§6.5): {@code {field, code, message}}. */
public record ProblemField(String field, String code, String message) {}
