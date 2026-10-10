/**
 * Cross-workstream Java contracts (ARCHITECTURE §5.3, CONTRACT). Interfaces and records only (plus the pure helpers
 * {@code CurrentPlayer} and {@code UnitMath}); implementations live in the owning feature packages. Every §18.3 wire
 * DTO is a record in {@code com.stocks.contract.<producer>.dto} (naming rule in §18.3).
 *
 * <p>Rules (§5.2): this package depends only on {@code com.stocks.common..}, other {@code com.stocks.contract..}
 * packages, the JDK and annotation/API types (Jackson annotations, Jakarta Validation, swagger {@code @Schema},
 * Spring Security core and the MCP SDK's {@code McpTransportContext} for {@code CurrentPlayer}). No {@code double} or
 * {@code float} anywhere. Until a workstream lands its real bean, {@code config.stubs.StubsAutoConfiguration}
 * provides a stub that throws {@code ApiException(NOT_IMPLEMENTED)} (§27.1).
 */
package com.stocks.contract;
