package com.stocks.contract.marketdata;

/** A subscription-budget lease; closing it releases the lease (idempotent). */
public interface LeaseHandle extends AutoCloseable {
  @Override
  void close();
}
