package com.stocks.contract.marketdata;

/**
 * Where a symbol's Time &amp; Sales tape comes from (§15.10): {@code SIP} consolidated prints (the Finnhub trade
 * stream, or Alpaca on {@code alpaca-sip}), {@code IEX} single-venue prints (the Alpaca IEX stream, a few percent of
 * the volume), {@code SIMULATED} the SIM market, {@code NONE} no trade stream covers the symbol right now.
 */
public enum TapeSource { SIP, IEX, SIMULATED, NONE }
