package com.stocks.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Production runs {@code spring.flyway.out-of-order=false} and has already applied the migrations in
 * {@link #APPLIED_IN_PRODUCTION} (§8 "Migrations after V1"). A new migration that sorts before the newest of them is
 * "ignored" by Flyway and fails validation at boot, so every new file must sort after it ({@code V3}, {@code V4}, ...).
 */
class MigrationOrderTest {

  /**
   * Applied in production (the first deploy, 2026-10, then V2 with the Phase 1 review fixes); never edit, rename or
   * delete these files.
   */
  static final Set<String> APPLIED_IN_PRODUCTION = Set.of("1", "1.0.1", "1.3.01", "1.4.01", "2");
  static final MigrationVersion NEWEST_IN_PRODUCTION = MigrationVersion.fromVersion("2");

  private static final Pattern FILE = Pattern.compile("V([0-9_]+)__[A-Za-z0-9_]+\\.sql");

  private static Map<String, List<MigrationVersion>> versionsByFolder() throws Exception {
    Map<String, List<MigrationVersion>> out = new HashMap<>();
    for (String folder : List.of("common", "h2", "postgresql")) {
      List<MigrationVersion> versions = new ArrayList<>();
      for (Resource r : new PathMatchingResourcePatternResolver()
          .getResources("classpath:db/migration/" + folder + "/*.sql")) {
        Matcher m = FILE.matcher(r.getFilename());
        assertThat(m.matches()).as("migration file name %s/%s", folder, r.getFilename()).isTrue();
        versions.add(MigrationVersion.fromVersion(m.group(1).replace('_', '.')));
      }
      out.put(folder, versions);
    }
    return out;
  }

  @Test
  void everyMigrationAfterTheProductionBaselineSortsAfterIt() throws Exception {
    for (Map.Entry<String, List<MigrationVersion>> e : versionsByFolder().entrySet()) {
      for (MigrationVersion v : e.getValue()) {
        if (APPLIED_IN_PRODUCTION.contains(v.getVersion())) {
          continue;
        }
        assertThat(v.compareTo(NEWEST_IN_PRODUCTION)).as("%s/V%s must sort after %s (prod out-of-order=false)",
            e.getKey(), v, NEWEST_IN_PRODUCTION).isPositive();
      }
    }
  }

  @Test
  void versionsAreUniquePerVendor() throws Exception {
    Map<String, List<MigrationVersion>> all = versionsByFolder();
    for (String vendor : List.of("h2", "postgresql")) {
      List<MigrationVersion> resolved = new ArrayList<>(all.get("common"));
      resolved.addAll(all.get(vendor));
      assertThat(resolved).as("common + %s", vendor).doesNotHaveDuplicates();
    }
  }

  /**
   * The real upgrade path: a database at the production baseline migrates with out-of-order off, as prod runs
   * (migrate validates first; a file sorting before the baseline would fail here as "ignored").
   */
  @Test
  void productionBaselineUpgradesWithOutOfOrderOff() throws Exception {
    String url = "jdbc:h2:mem:migration-order;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
        + "DB_CLOSE_DELAY=-1";
    // one connection for the whole test: H2 2.4.240 invalidates CHECKs when their creating connection closes (§28 #24)
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      SingleConnectionDataSource ds = new SingleConnectionDataSource(c, true);
      String[] locations = {"classpath:db/migration/common", "classpath:db/migration/h2"};
      Flyway.configure().dataSource(ds).locations(locations).target(NEWEST_IN_PRODUCTION).load().migrate();
      Flyway prod = Flyway.configure().dataSource(ds).locations(locations).outOfOrder(false).load();
      prod.migrate();
      assertThat(prod.info().pending()).isEmpty();
    }
  }
}
