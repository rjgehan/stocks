package com.stocks.trading.engine.write;

import com.stocks.common.domain.AccountEventType;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.trading.dto.ActivityDetailDto;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.trading.engine.EngineThread;
import java.sql.Types;
import java.time.Instant;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only writer of {@code account_event} (append-only, §8.1): the non-cash history shown in activities (§18.2).
 * The payload is the §18.3 {@code ActivityDetail} for its type, serialized with Boot's mapper. Engine thread only.
 */
@Component
public class AccountEventWriter {

  private final JdbcClient jdbc;
  private final PublicId publicIds;
  private final JsonMapper json;

  public AccountEventWriter(JdbcClient jdbc, PublicId publicIds, JsonMapper json) {
    this.jdbc = jdbc;
    this.publicIds = publicIds;
    this.json = json;
  }

  /** Returns the new event's public id ({@code aev_…}). */
  public String insert(long accountId, AccountEventType type, Instant at, String symbolOrNull, ActorKind actor,
                       ActivityDetailDto payload) {
    EngineThread.assertCurrent();
    String publicId = publicIds.next(Prefix.ACCOUNT_EVENT);
    jdbc.sql("""
            INSERT INTO account_event (public_id, account_id, event_type, occurred_at, symbol, actor_kind, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?)""")
        .params(publicId, accountId, type.name(), Jdbc.ts(at), new SqlParameterValue(Types.VARCHAR, symbolOrNull),
            actor.name(), json.writerFor(ActivityDetailDto.class).writeValueAsString(payload))
        .update();
    return publicId;
  }
}
