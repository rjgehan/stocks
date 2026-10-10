package com.stocks.contract.identity.dto;

/** §18.3 {@code ApiKeyCreated}. {@code token} ({@code stk_<keyId>_<secret>}) is shown once. */
public record ApiKeyCreatedDto(ApiKeyInfoDto key, String token, ApiKeySnippetsDto snippets) {}
