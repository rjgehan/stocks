package com.stocks.common.events;

/**
 * A browser session got a new id (§17.3 login fixation defence, password change). Realtime re-binds the WebSockets
 * the session opened, so a later logout or revocation of the new id still closes them.
 */
public record SessionIdChanged(String oldSessionId, String newSessionId) {}
