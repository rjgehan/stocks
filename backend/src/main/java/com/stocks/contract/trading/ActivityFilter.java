package com.stocks.contract.trading;

import com.stocks.common.domain.ActivityType;
import java.time.LocalDate;
import java.util.Set;

/** {@code typesOrEmpty} empty = every type. Dates are ET. */
public record ActivityFilter(Set<ActivityType> typesOrEmpty, LocalDate fromOrNull, LocalDate toOrNull) {}
