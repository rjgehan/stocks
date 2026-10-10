package com.stocks.identity;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.Scopes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The dev demo league ({@code stocks.bootstrap.seed-demo=true}, profile {@code dev}, §23.2): humans {@code alice},
 * {@code bob} and {@code carol} (password {@code password123!}), the AI players {@code claude} and {@code chatgpt},
 * owned by the bootstrap admin, each with an all-scope API key, and a reusable invite link (50 uses, 30 days) to try
 * the signup flow. Every player gets the league's starting cash and the default watchlist through
 * {@link AccountProvisioner}. Idempotent: it creates only what is missing, so deleting {@code backend/.data/} costs
 * nothing. Credentials go to {@code .data/dev-keys.txt} (relative to the working directory, next to the dev H2 file);
 * a bot key or an invite code is printed there once, when it is issued.
 */
@Component
@Order(20)
@ConditionalOnBooleanProperty("stocks.bootstrap.seed-demo")
public class DemoSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

  public static final String DEMO_PASSWORD = "password123!";
  public static final Path KEYS_FILE = Path.of(".data", "dev-keys.txt");

  record Human(String handle, String displayName) {}

  record Bot(String handle, String displayName, String aiModel) {}

  static final List<Human> HUMANS = List.of(new Human("alice", "Alice"), new Human("bob", "Bob"),
      new Human("carol", "Carol"));
  static final int INVITE_USES = 50;
  static final int INVITE_HOURS = 720;
  static final List<Bot> BOTS = List.of(new Bot("claude", "Claude", "Claude"), new Bot("chatgpt", "ChatGPT", "ChatGPT"));

  private final PlayerService players;
  private final ApiKeyService apiKeys;
  private final AccountProvisioner accounts;
  private final BootstrapAdminRunner bootstrap;
  private final InviteService invites;
  private final StocksProperties properties;
  private final TimeSource time;
  private final Path keysFile;

  @Autowired
  public DemoSeeder(PlayerService players, ApiKeyService apiKeys, AccountProvisioner accounts,
                    BootstrapAdminRunner bootstrap, InviteService invites, StocksProperties properties,
                    TimeSource time) {
    this(players, apiKeys, accounts, bootstrap, invites, properties, time, KEYS_FILE);
  }

  DemoSeeder(PlayerService players, ApiKeyService apiKeys, AccountProvisioner accounts,
             BootstrapAdminRunner bootstrap, InviteService invites, StocksProperties properties, TimeSource time,
             Path keysFile) {
    this.players = players;
    this.apiKeys = apiKeys;
    this.accounts = accounts;
    this.bootstrap = bootstrap;
    this.invites = invites;
    this.properties = properties;
    this.time = time;
    this.keysFile = keysFile;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  /** @return the lines appended to the keys file (empty when nothing new was created) */
  public List<String> seed() {
    List<String> lines = new ArrayList<>();
    Optional<PlayerRecord> admin = bootstrap.ensureAdmin();
    for (Human h : HUMANS) {
      PlayerRecord p = players.byHandle(h.handle()).orElse(null);
      if (p == null) {
        p = players.createHuman(h.handle(), h.displayName(), DEMO_PASSWORD, PlayerRole.PLAYER);
        lines.add(h.handle() + " / " + DEMO_PASSWORD);
      }
      accounts.ensureAccount(p.id(), admin.map(PlayerRecord::id).orElse(p.id()));
    }
    if (admin.isEmpty()) {
      log.warn("Demo seed: no bootstrap admin, so the AI players (claude, chatgpt) are skipped");
    } else if (!properties.security().hasApiKeyPepper()) {
      log.warn("Demo seed: no API_KEY_PEPPER, so the AI players (claude, chatgpt) are skipped");
    } else {
      long adminId = admin.get().id();
      for (Bot b : BOTS) {
        PlayerRecord p = players.byHandle(b.handle()).orElse(null);
        if (p == null) {
          p = players.createAi(b.handle(), b.displayName(), b.aiModel(), adminId);
        } else if (p.kind() != PlayerKind.AI) {
          log.warn("Demo seed: '{}' exists and is not an AI player; skipped", b.handle());
          continue;
        }
        accounts.ensureAccount(p.id(), adminId);
        if (!apiKeys.hasActiveKey(p.id())) {
          ApiKeyService.IssuedKey key = apiKeys.issue(p.id(), "dev-seed", Scopes.ALL, null, adminId);
          lines.add(b.handle() + " (AI) API key: " + key.token());
        }
      }
    }
    if (admin.isPresent() && !invites.anyUsable()) {
      var invite = invites.create(INVITE_USES, INVITE_HOURS, admin.get().id());
      lines.add("invite link (" + INVITE_USES + " uses, until " + invite.expiresAt() + "): " + invite.url());
    }
    if (!lines.isEmpty()) {
      write(lines, admin);
    }
    return lines;
  }

  private void write(List<String> lines, Optional<PlayerRecord> admin) {
    List<String> out = new ArrayList<>();
    boolean fresh = !Files.exists(keysFile);
    if (fresh) {
      out.add("# stocks dev credentials, written by DemoSeeder (profile dev only). Keys appear once, when issued.");
      out.add("# Delete backend/.data/ to start a fresh dev league.");
      admin.ifPresent(a -> out.add(a.handle() + " (ADMIN) / " + properties.bootstrap().adminPassword()));
    }
    out.add("# " + time.now());
    out.addAll(lines);
    try {
      Path dir = keysFile.toAbsolutePath().getParent();
      Files.createDirectories(dir);
      Files.write(keysFile, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      if (fresh) {
        try {
          Files.setPosixFilePermissions(keysFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
          // non-POSIX file system
        }
      }
      log.info("Demo seed: wrote {} new credential line(s) to {}", lines.size(), keysFile.toAbsolutePath());
    } catch (IOException e) {
      log.warn("Demo seed: could not write {} ({}); new credentials follow once:\n{}", keysFile, e.getMessage(),
          String.join("\n", lines));
    }
  }
}
