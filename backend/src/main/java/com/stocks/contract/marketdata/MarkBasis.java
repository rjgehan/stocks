package com.stocks.contract.marketdata;

/** Where a live mark came from (§15.5 {@code MarkService}). */
public enum MarkBasis { CONSOLIDATED_LAST, IEX_MID, IEX_LAST, PREV_CLOSE, DAY_END, SIMULATED }
