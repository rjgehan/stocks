package com.stocks.contract.manualbot.dto;

/**
 * §20.8: the outcome of one placed row. {@code REPLAYED}: the order already existed (same client order id);
 * {@code CANCEL_REQUESTED}: the cancel waits out the cancel latency (§11.10).
 */
public enum ManualResultStatus { PLACED, REPLAYED, CANCELLED, CANCEL_REQUESTED, FAILED }
