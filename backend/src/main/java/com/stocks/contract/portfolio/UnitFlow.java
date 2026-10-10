package com.stocks.contract.portfolio;

import java.math.BigDecimal;

/**
 * The unit-accounting effect of one external flow or run opening (§16.2), computed by {@link UnitMath#contribution}
 * or {@link UnitMath#openRun}. The engine copies it into {@code cash_transfer} ({@code unit_value},
 * {@code units_delta}, {@code unit_series}, {@code rebased}) and {@code account} ({@code units}, {@code unit_series}).
 * Units and unit value at scale 18 ({@code Rounding.UNIT}).
 *
 * @param unitValue       U at the flow ({@code cash_transfer.unit_value}); 1 on a rebase
 * @param unitsDelta      {@code cash_transfer.units_delta}; negative for a withdrawal; {@code F − units⁻} on a rebase
 * @param unitsAfter      {@code account.units} after the flow ({@code units⁻ + unitsDelta}, never negative)
 * @param unitSeriesAfter {@code account.unit_series} / {@code cash_transfer.unit_series} after the flow
 * @param rebased         a contribution into a wiped-out account (E⁻ = 0, units⁻ &gt; 0) or a run opened after a run
 *                        that closed at U = 0: a new unit series starts at U = 1
 */
public record UnitFlow(BigDecimal unitValue, BigDecimal unitsDelta, BigDecimal unitsAfter, int unitSeriesAfter,
                       boolean rebased) {}
