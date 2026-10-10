package com.stocks.contract.realtime.dto;

import java.time.Instant;

/** §19 {@code /user/queue/admin} payload (admins only). */
public record AdminAlertDto(Level level, String title, String body, Instant at) {

  public enum Level { INFO, WARN, ERROR }
}
