package com.stocks.contract.manualbot.dto;

import com.stocks.common.domain.SessionType;
import java.time.Instant;
import java.util.List;

/**
 * §20.8 {@code ManualSession}: one prompt, the pasted reply, the parsed rows and their results. Public (under the
 * bot's History). List shape: {@code untrusted.prompt} and {@code untrusted.reply} are null; detail shape: both
 * filled. {@code issues} are the session-level ones; each row carries its own.
 */
public record ManualSessionDto(String sessionId, String botHandle, String operatorHandle, ManualSessionStatus status,
                               String aiModel, SessionType marketSession, Instant createdAt, Instant repliedAt,
                               Instant executedAt, String promptSha256, int promptChars, Integer replyChars,
                               UntrustedManualTextDto untrusted, List<ManualIssueDto> issues,
                               List<ManualActionDto> actions, String journalNoteId) {}
