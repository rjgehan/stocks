package com.stocks.contract.trading.dto;

import com.stocks.common.domain.PlayerKind;
import java.util.Set;

/** {@code target: {scope: "ALL", kinds?}}: ACTIVE accounts of ACTIVE/FROZEN players; {@code kinds} null = both. */
public record AllTargetRequest(Set<PlayerKind> kinds) implements TransferTargetRequest {}
