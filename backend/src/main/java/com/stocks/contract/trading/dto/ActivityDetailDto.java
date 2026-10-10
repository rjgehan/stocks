package com.stocks.contract.trading.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * §18.3 {@code ActivityDetail}: the {@code account_event.payload}, one shape per {@code event_type} (CONTRACT; WS3
 * writes it, {@code ActivityDtoMapper} reads it). The JSON discriminator {@code type} is the
 * {@code AccountEventType} name.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SplitDetailDto.class, name = "SPLIT"),
    @JsonSubTypes.Type(value = NameChangeDetailDto.class, name = "NAME_CHANGE"),
    @JsonSubTypes.Type(value = GfvDetailDto.class, name = "GFV"),
    @JsonSubTypes.Type(value = ResetDetailDto.class, name = "RESET"),
    @JsonSubTypes.Type(value = RestrictionDetailDto.class, name = "RESTRICTION"),
    @JsonSubTypes.Type(value = AdminViewDetailDto.class, name = "ADMIN_VIEW")
})
public sealed interface ActivityDetailDto
    permits SplitDetailDto, NameChangeDetailDto, GfvDetailDto, ResetDetailDto, RestrictionDetailDto,
            AdminViewDetailDto {}
