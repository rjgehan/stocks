package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.marketdata.model.Asset;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@code asset} table (§8.1): every tradable symbol must have a row ({@code trade_order.symbol} references it).
 * Upserts are batched {@code MERGE}s with every parameter cast (§8).
 */
public class AssetRepository {

  /** A stored row with its bookkeeping columns. */
  public record Row(Asset asset, BigDecimal avgDollarVolume, String source) {}

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  public AssetRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(dataSource);
    this.template = new JdbcTemplate(dataSource);
  }

  public List<Row> loadAll() {
    return jdbc.sql("""
            SELECT symbol, name, exchange, asset_type, tradable, fractionable, shortable, easy_to_borrow, marginable,
                   liquidity_tier, avg_dollar_volume, status, source FROM asset""")
        .query(AssetRepository::map).list();
  }

  public long count() {
    return jdbc.sql("SELECT COUNT(*) FROM asset").query(Long.class).single();
  }

  public long countBySource(String source) {
    return jdbc.sql("SELECT COUNT(*) FROM asset WHERE source = ?").param(source).query(Long.class).single();
  }

  /** Inserts or updates rows; {@code adv} may hold a 20-day average dollar volume per symbol. */
  public void upsert(List<Asset> assets, String source, Map<String, BigDecimal> adv, Instant now) {
    if (assets.isEmpty()) {
      return;
    }
    String sql = """
        MERGE INTO asset a
        USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS VARCHAR(200)), CAST(? AS VARCHAR(12)), CAST(? AS VARCHAR(12)),
                       CAST(? AS BOOLEAN), CAST(? AS BOOLEAN), CAST(? AS BOOLEAN), CAST(? AS BOOLEAN), CAST(? AS BOOLEAN),
                       CAST(? AS VARCHAR(6)), CAST(? AS NUMERIC(19,2)), CAST(? AS VARCHAR(10)), CAST(? AS VARCHAR(12)),
                       CAST(? AS TIMESTAMP WITH TIME ZONE)))
              AS v(symbol, name, exchange, asset_type, tradable, fractionable, shortable, easy_to_borrow, marginable,
                   liquidity_tier, avg_dollar_volume, status, source, updated_at)
        ON a.symbol = v.symbol
        WHEN MATCHED THEN UPDATE SET name = v.name, exchange = v.exchange, asset_type = v.asset_type,
             tradable = v.tradable, fractionable = v.fractionable, shortable = v.shortable,
             easy_to_borrow = v.easy_to_borrow, marginable = v.marginable, liquidity_tier = v.liquidity_tier,
             avg_dollar_volume = COALESCE(v.avg_dollar_volume, a.avg_dollar_volume), status = v.status,
             source = v.source, updated_at = v.updated_at
        WHEN NOT MATCHED THEN INSERT (symbol, name, exchange, asset_type, tradable, fractionable, shortable,
             easy_to_borrow, marginable, liquidity_tier, avg_dollar_volume, status, source, updated_at)
             VALUES (v.symbol, v.name, v.exchange, v.asset_type, v.tradable, v.fractionable, v.shortable,
                     v.easy_to_borrow, v.marginable, v.liquidity_tier, v.avg_dollar_volume, v.status, v.source,
                     v.updated_at)""";
    List<Object[]> args = new ArrayList<>(assets.size());
    for (Asset a : assets) {
      args.add(new Object[] {a.symbol(), truncate(a.name(), 200), truncate(a.exchange(), 12),
          (a.type() == null ? AssetType.UNKNOWN : a.type()).name(), a.tradable(), a.fractionable(), a.shortable(),
          a.easyToBorrow(), a.marginable(), (a.tier() == null ? LiquidityTier.SMALL : a.tier()).name(),
          new SqlParameterValue(Types.NUMERIC, adv == null ? null : adv.get(a.symbol())),
          a.active() ? "ACTIVE" : "INACTIVE", source, Jdbc.ts(now)});
    }
    for (int i = 0; i < args.size(); i += 500) {
      template.batchUpdate(sql, args.subList(i, Math.min(args.size(), i + 500)));
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static Row map(ResultSet rs, int n) throws SQLException {
    Asset a = new Asset(rs.getString("symbol"), rs.getString("name"), rs.getString("exchange"),
        AssetType.valueOf(rs.getString("asset_type")), rs.getBoolean("tradable"), rs.getBoolean("fractionable"),
        rs.getBoolean("shortable"), rs.getBoolean("easy_to_borrow"), rs.getBoolean("marginable"),
        LiquidityTier.valueOf(rs.getString("liquidity_tier")), "ACTIVE".equals(rs.getString("status")));
    return new Row(a, rs.getBigDecimal("avg_dollar_volume"), rs.getString("source"));
  }
}
