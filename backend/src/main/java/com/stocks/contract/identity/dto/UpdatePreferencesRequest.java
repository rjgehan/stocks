package com.stocks.contract.identity.dto;

import com.stocks.common.domain.TimeInForce;

/** {@code Partial<Preferences>} in {@code PATCH /me} (§18.1): null = unchanged. */
public record UpdatePreferencesRequest(PreferencesDto.Theme theme, PreferencesDto.UpDown upDown, Boolean confirmOrders,
                                       PreferencesDto.DefaultOrderType defaultOrderType,
                                       TimeInForce defaultTimeInForce, PreferencesDto.QtyMode defaultQtyMode) {}
