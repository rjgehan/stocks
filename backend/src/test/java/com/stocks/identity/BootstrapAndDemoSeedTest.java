package com.stocks.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.audit.AuditService;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.config.StocksProperties;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.security.ApiKeyAuthenticationProvider;
import com.stocks.security.ApiKeyAuthenticationToken;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The bootstrap admin (§23.3: created from config through the normal password rules, prod fail-fast without one)
 * and the idempotent dev demo seed (§23.2), on a fresh database with the WS3/WS4 contracts still Day-0 stubs.
 */
class BootstrapAndDemoSeedTest {

  TestDb db;
  MutableTimeSource time;
  PlayerService players;
  AuditService audit;
  AccountProvisioner accounts;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt",
        java.util.Map.of("bcrypt", new BCryptPasswordEncoder(4)));
    players = new PlayerService(db.jdbc(), time, new PublicId(time), encoder);
    audit = new AuditService(db.jdbc(), time, JacksonConfig.newMapper());
    accounts = new AccountProvisioner(NotImplementedStub.of(AccountQueries.class, "WS3"),
        NotImplementedStub.of(AccountService.class, "WS3"), NotImplementedStub.of(SettingsService.class, "WS1"),
        NotImplementedStub.of(WatchlistService.class, "WS4"), TestProperties.stocks());
  }

  BootstrapAdminRunner runner(StocksProperties props, String... profiles) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    return new BootstrapAdminRunner(players, accounts, audit, props, env);
  }

  @Test
  void createsTheAdminOnceFromConfig() {
    StocksProperties props = TestProperties.stocks("stocks.bootstrap.admin-handle", "Owner",
        "stocks.bootstrap.admin-password", "correct-horse-battery");
    PlayerRecord admin = runner(props).ensureAdmin().orElseThrow();
    assertThat(admin.handle()).isEqualTo("owner");
    assertThat(admin.role()).isEqualTo(PlayerRole.ADMIN);
    assertThat(admin.kind()).isEqualTo(PlayerKind.HUMAN);
    assertThat(players.passwordMatches(admin, "correct-horse-battery")).isTrue();
    assertThat(runner(props).ensureAdmin()).get().extracting(PlayerRecord::id).isEqualTo(admin.id());
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM player WHERE role = 'ADMIN'").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM audit_log WHERE action = 'BOOTSTRAP_ADMIN'")
        .query(Long.class).single()).isEqualTo(1);
    assertThat(accounts.ensureAccount(admin.id(), admin.id())).isEqualTo(AccountProvisioner.Outcome.PENDING);
  }

  @Test
  void aShortPasswordStopsTheBoot() {
    StocksProperties props = TestProperties.stocks("stocks.bootstrap.admin-handle", "admin",
        "stocks.bootstrap.admin-password", "short123");
    assertThatThrownBy(() -> runner(props).ensureAdmin()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WEAK_PASSWORD");
    assertThat(players.anyAdmin()).isFalse();
  }

  @Test
  void prodWithoutAnyAdminOrBootstrapVariablesFailsFast() {
    StocksProperties none = TestProperties.stocks();
    assertThatThrownBy(() -> runner(none, "prod").ensureAdmin()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADMIN_BOOTSTRAP_USERNAME");
    assertThat(runner(none, "dev").ensureAdmin()).isEmpty();
  }

  @Test
  void aHandleTakenByANonAdminIsRefused() {
    players.createHuman("admin", "admin", "some-long-password", PlayerRole.PLAYER);
    StocksProperties props = TestProperties.stocks("stocks.bootstrap.admin-handle", "admin",
        "stocks.bootstrap.admin-password", "correct-horse-battery");
    assertThatThrownBy(() -> runner(props).ensureAdmin()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-admin");
  }

  @Test
  void theDemoSeedIsIdempotentAndItsBotKeysWork(@TempDir Path dir) throws Exception {
    StocksProperties props = TestProperties.stocks("stocks.bootstrap.admin-handle", "admin",
        "stocks.bootstrap.admin-password", "admin-dev-123", "stocks.bootstrap.seed-demo", "true");
    ApiKeyService apiKeys = new ApiKeyService(db.jdbc(), time, props);
    Path keys = dir.resolve(".data/dev-keys.txt");
    DemoSeeder seeder = new DemoSeeder(players, apiKeys, accounts, runner(props), props, time, keys);

    List<String> first = seeder.seed();
    assertThat(first).hasSize(4);
    assertThat(players.byHandle("alice")).get().extracting(PlayerRecord::kind).isEqualTo(PlayerKind.HUMAN);
    assertThat(players.byHandle("claude")).get().satisfies(p -> {
      assertThat(p.kind()).isEqualTo(PlayerKind.AI);
      assertThat(p.passwordHash()).isNull();
      assertThat(p.ownerPlayerId()).isEqualTo(players.byHandle("admin").orElseThrow().id());
    });
    String file = Files.readString(keys);
    assertThat(file).contains("admin (ADMIN) / admin-dev-123").contains("alice / password123!")
        .contains("claude (AI) API key: stk_").contains("chatgpt (AI) API key: stk_");

    assertThat(seeder.seed()).as("second run creates nothing").isEmpty();
    assertThat(Files.readString(keys)).isEqualTo(file);

    String token = first.stream().filter(l -> l.startsWith("claude")).findFirst().orElseThrow()
        .replaceAll(".*API key: ", "");
    var auth = new ApiKeyAuthenticationProvider(db.jdbc(), time, props)
        .authenticate(new ApiKeyAuthenticationToken(token));
    PlayerPrincipal p = (PlayerPrincipal) auth.getPrincipal();
    assertThat(p.handle()).isEqualTo("claude");
    assertThat(p.authMethod()).isEqualTo(AuthMethod.API_KEY);
    assertThat(p.scopes()).hasSize(4);
  }

  @Test
  void passwordAndHandleRules() {
    assertThatThrownBy(() -> PlayerService.validatePassword("123456789")).hasMessageContaining("at least 10");
    assertThatThrownBy(() -> PlayerService.validatePassword("é".repeat(37))).hasMessageContaining("72 bytes");
    PlayerService.validatePassword("0123456789");
    assertThatThrownBy(() -> players.createHuman("Bad Handle", "Name", "long-enough-pw", PlayerRole.PLAYER))
        .hasMessageContaining("lowercase");
    players.createHuman("dupe", "Dupe", "long-enough-pw", PlayerRole.PLAYER);
    assertThatThrownBy(() -> players.createHuman("DUPE", "Dupe", "long-enough-pw", PlayerRole.PLAYER))
        .hasMessageContaining("taken");
  }
}
