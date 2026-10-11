package com.stocks.contract.realtime.dto;

/**
 * {@code event: bye} (§19.3), right before the server closes a stream: {@code MAX_AGE} (reconnect with
 * {@code Last-Event-ID}), {@code REVOKED} (key revoked or expired, player frozen or disabled), {@code SLOW_CONSUMER}
 * (the client fell behind; resume from the last id) or {@code SHUTDOWN}.
 */
public record StreamByeDto(Reason reason) {

  public enum Reason { MAX_AGE, REVOKED, SLOW_CONSUMER, SHUTDOWN }
}
