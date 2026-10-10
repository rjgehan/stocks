package com.stocks.trading.engine;

import com.stocks.contract.trading.EngineControl;
import com.stocks.contract.trading.EngineStatus;
import org.springframework.stereotype.Service;

/** Backs {@code /admin/engine/*} and {@code SystemStatus.engine} (§21.1); WS1's controllers audit the actions. */
@Service
public class EngineControlImpl implements EngineControl {

  private final EngineCalls calls;

  public EngineControlImpl(EngineCalls calls) {
    this.calls = calls;
  }

  @Override
  public EngineStatus status() {
    return calls.engine().status();
  }

  @Override
  public EngineStatus halt(String reason, long actorPlayerId) {
    String r = reason == null || reason.isBlank() ? "Halted by admin" : reason.trim();
    return calls.call(new EngineCommand.HaltEngineCmd(r, actorPlayerId));
  }

  @Override
  public EngineStatus resume(long actorPlayerId) {
    return calls.call(new EngineCommand.ResumeEngineCmd(actorPlayerId));
  }
}
