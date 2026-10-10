package com.stocks.contract.realtime.dto;

/**
 * {@code event: reset} (§19.1): the {@code Last-Event-ID} could not be honoured, nothing was replayed; refetch the
 * REST state. {@code reason}: {@code RESTARTED} (another server start), {@code EXPIRED} (older than the buffer) or
 * {@code UNKNOWN} (malformed or from the future).
 */
public record StreamResetDto(Reason reason, String lastEventId) {

  public enum Reason { RESTARTED, EXPIRED, UNKNOWN }
}
