package com.stocks.contract.trading;

/** Backs {@code /admin/engine/*} and {@code SystemStatus.engine} (WS3). */
public interface EngineControl {

  EngineStatus status();

  /** HaltEngineCmd. */
  EngineStatus halt(String reason, long actorPlayerId);

  /** CheckInvariantsCmd, then ResumeEngineCmd if ok. */
  EngineStatus resume(long actorPlayerId);
}
