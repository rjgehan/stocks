package com.stocks.apilog;

import com.stocks.apilog.ApiAccessLogRepository.Row;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.agents.dto.ApiCallDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Recent API calls" (§17.10): a player's own keys and the AI players they own ({@code GET /me/api-calls}), or every
 * player for the admin ({@code GET /admin/api-calls}). Pending buckets are flushed before every read, so a call made
 * a moment ago is listed.
 */
@Service
public class ApiCallService {

  private static final Pattern OK = Pattern.compile("^(OK|[23][0-9][0-9])$");

  private final ApiAccessLog log;
  private final ApiAccessLogRepository repository;
  private final JdbcClient jdbc;

  public ApiCallService(ApiAccessLog log, ApiAccessLogRepository repository, JdbcClient jdbc) {
    this.log = log;
    this.repository = repository;
    this.jdbc = jdbc;
  }

  private record Owned(long id, String handle) {}

  /** The caller's calls and their bots'; {@code handle} narrows to one of them ({@code PLAYER_NOT_FOUND} else). */
  public Page<ApiCallDto> mine(long playerId, String handle, Cursor cursor, int limit) {
    List<Owned> owned = new ArrayList<>(jdbc.sql("SELECT id, handle FROM player WHERE id = ? OR owner_player_id = ? "
            + "ORDER BY id").params(playerId, playerId)
        .query((rs, n) -> new Owned(rs.getLong("id"), rs.getString("handle"))).list());
    List<Long> ids = owned.stream().map(Owned::id).toList();
    if (handle != null && !handle.isBlank()) {
      String wanted = handle.strip().toLowerCase(Locale.ROOT);
      ids = owned.stream().filter(o -> o.handle().equals(wanted)).map(Owned::id).toList();
      if (ids.isEmpty()) {
        throw notFound(handle);
      }
    }
    return page(ids, cursor, limit);
  }

  /** Every player's calls; {@code handle} narrows to one player (any status). */
  public Page<ApiCallDto> all(String handle, Cursor cursor, int limit) {
    List<Long> ids = null;
    if (handle != null && !handle.isBlank()) {
      Optional<Long> id = jdbc.sql("SELECT id FROM player WHERE handle = ?")
          .param(handle.strip().toLowerCase(Locale.ROOT)).query(Long.class).optional();
      ids = List.of(id.orElseThrow(() -> notFound(handle)));
    }
    return page(ids, cursor, limit);
  }

  private Page<ApiCallDto> page(List<Long> playerIds, Cursor cursor, int limit) {
    if (!log.enabled()) {
      return Page.empty();
    }
    log.flush();
    List<Row> rows = repository.page(playerIds, cursor, limit + 1);
    return Page.fromLookahead(rows, limit, r -> Cursor.of(r.lastAt(), r.id())).map(ApiCallService::dto);
  }

  static ApiCallDto dto(Row r) {
    long avg = r.calls() == 0 ? 0 : (r.totalMs() + r.calls() / 2) / r.calls();
    return new ApiCallDto(Long.toString(r.id()), r.hourStart(), r.handle(), r.displayName(), r.kind(), r.keyRef(),
        r.keyName(), r.channel(), r.endpoint(), r.outcome(), OK.matcher(r.outcome()).matches(), r.calls(), avg,
        r.maxMs(), r.firstAt(), r.lastAt(), r.lastIp(), r.lastClient());
  }

  private static ApiException notFound(String handle) {
    return ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player '" + Text.clip(handle, 40) + "' among yours.");
  }
}
