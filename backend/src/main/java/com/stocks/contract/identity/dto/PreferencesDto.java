package com.stocks.contract.identity.dto;

import com.stocks.common.domain.TimeInForce;

/** §18.3 {@code Preferences}; stored as JSON in {@code player.preferences}. */
public record PreferencesDto(Theme theme, UpDown upDown, boolean confirmOrders, DefaultOrderType defaultOrderType,
                             TimeInForce defaultTimeInForce, QtyMode defaultQtyMode) {

  public enum Theme { DARK, LIGHT, SYSTEM }

  public enum UpDown { GREEN_UP, RED_UP }

  public enum DefaultOrderType { LIMIT, MARKET }

  public enum QtyMode { SHARES, AMOUNT }
}
