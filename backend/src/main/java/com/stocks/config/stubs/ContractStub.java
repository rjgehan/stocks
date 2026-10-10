package com.stocks.config.stubs;

/**
 * Marker for a Day-0 default bean from {@link StubsAutoConfiguration} (§27.1). Every stub implements it, so a test or
 * {@code /admin/system} can list which contracts are still unimplemented; at M4 no bean may implement it.
 */
public interface ContractStub {

  /** The workstream that must replace this bean (§27.2), e.g. {@code "WS3"}. */
  String stubOwner();
}
