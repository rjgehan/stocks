package com.stocks.contract.trading.dto;

/** {@code ActivityDetail} {@code type: "ADMIN_VIEW"} (§21.3 break-glass). {@code admin} is the admin's handle. */
public record AdminViewDetailDto(String admin, String reason) implements ActivityDetailDto {}
