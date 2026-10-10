package com.stocks.contract.trading.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * §21.1 {@code CashTransferRequest.target}: {@code {scope: "SINGLE", handle}} or {@code {scope: "ALL", kinds?}}. The
 * JSON discriminator {@code scope} is the {@code TransferScope} name.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "scope")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SingleTargetRequest.class, name = "SINGLE"),
    @JsonSubTypes.Type(value = AllTargetRequest.class, name = "ALL")
})
public sealed interface TransferTargetRequest permits SingleTargetRequest, AllTargetRequest {}
