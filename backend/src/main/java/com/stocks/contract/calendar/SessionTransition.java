package com.stocks.contract.calendar;

import com.stocks.common.domain.TransitionType;
import java.time.Instant;
import java.time.LocalDate;

/** A session boundary (§9.4): PRE_OPEN, RTH_OPEN, RTH_CLOSE or POST_CLOSE of a trading day. */
public record SessionTransition(LocalDate tradingDate, TransitionType type, Instant at) {}
