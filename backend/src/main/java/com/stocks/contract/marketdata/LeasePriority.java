package com.stocks.contract.marketdata;

/** Subscription-budget lease priorities, highest first (§15.5 {@code SubscriptionBudgetManager}). */
public enum LeasePriority { PINNED, ORDER_TRIGGER, ORDER_RESTING, VIEWING, HOLDING, LIST }
