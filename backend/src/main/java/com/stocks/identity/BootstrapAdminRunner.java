package com.stocks.identity;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.error.ApiException;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.RequestMeta;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Creates the first human ADMIN from {@code stocks.bootstrap.admin-handle}/{@code admin-password}
 * ({@code ADMIN_BOOTSTRAP_USERNAME}/{@code ADMIN_BOOTSTRAP_PASSWORD}, §23.3) when no admin exists. The password goes
 * through the normal {@link PlayerService} rules (at least 10 characters, §17.3); a bad value stops the boot with a
 * clear message. Once any admin exists the variables are ignored (the admin UI owns players from then on).
 * The dev profile defaults to {@code admin}/{@code admin-dev-123}.
 *
 * <p>In {@code prod}, no admin and no bootstrap variables is a fail-fast (§23.2). Each boot also makes sure the
 * bootstrap admin has an account ({@link AccountProvisioner}; a no-op until WS3 lands).
 */
@Component
@Order(10)
public class BootstrapAdminRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final PlayerService players;
  private final AccountProvisioner accounts;
  private final AuditService audit;
  private final StocksProperties properties;
  private final Environment environment;

  public BootstrapAdminRunner(PlayerService players, AccountProvisioner accounts, AuditService audit,
                              StocksProperties properties, Environment environment) {
    this.players = players;
    this.accounts = accounts;
    this.audit = audit;
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensureAdmin();
  }

  /** Creates the bootstrap admin if needed; returns it when it exists (created now or earlier). */
  public Optional<PlayerRecord> ensureAdmin() {
    StocksProperties.Bootstrap b = properties.bootstrap();
    if (players.anyAdmin()) {
      Optional<PlayerRecord> admin = b.hasAdminCredentials()
          ? players.byHandle(b.adminHandle()).filter(p -> p.role() == PlayerRole.ADMIN) : Optional.empty();
      admin.ifPresent(p -> accounts.ensureAccount(p.id(), p.id()));
      return admin;
    }
    if (!b.hasAdminCredentials()) {
      if (environment.acceptsProfiles(Profiles.of("prod"))) {
        throw new IllegalStateException("No admin exists and ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD "
            + "are not set (§23.2). Set both for the first boot.");
      }
      log.warn("No admin exists. Set ADMIN_BOOTSTRAP_USERNAME and ADMIN_BOOTSTRAP_PASSWORD to create one.");
      return Optional.empty();
    }
    String handle = PlayerService.normalizeHandle(b.adminHandle());
    if (players.byHandle(handle).isPresent()) {
      throw new IllegalStateException("ADMIN_BOOTSTRAP_USERNAME '" + handle + "' belongs to an existing non-admin "
          + "player; choose another handle.");
    }
    PlayerRecord admin;
    try {
      admin = players.createHuman(handle, handle, b.adminPassword(), PlayerRole.ADMIN);
    } catch (ApiException e) {
      throw new IllegalStateException("Cannot create the bootstrap admin from ADMIN_BOOTSTRAP_USERNAME / "
          + "ADMIN_BOOTSTRAP_PASSWORD: " + e.code() + ": " + e.detail(), e);
    }
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "BOOTSTRAP_ADMIN", AuditOutcome.OK)
        .withActor(admin.id(), admin.kind()).withMeta(RequestMeta.system(null)).withTarget("player", admin.publicId()));
    log.info("Created the bootstrap admin '{}'", handle);
    accounts.ensureAccount(admin.id(), admin.id());
    return Optional.of(admin);
  }
}
