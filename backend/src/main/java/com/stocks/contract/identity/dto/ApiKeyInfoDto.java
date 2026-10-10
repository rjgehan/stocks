package com.stocks.contract.identity.dto;

import java.time.Instant;
import java.util.List;

/** §18.3 {@code ApiKeyInfo}. {@code keyId} is the key's public id (§17.2), never the secret. */
public record ApiKeyInfoDto(String keyId, String name, List<String> scopes, Instant createdAt, Instant expiresAt,
                            Instant lastUsedAt, String lastUsedIp, Instant revokedAt) {}
