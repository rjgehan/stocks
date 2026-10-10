package com.stocks.security;

import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.testkit.TestClockConfig;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints, one per §17/§18 route family, so the chains can be tested before the feature controllers
 * exist. Shared by the security ITs (same imports → one cached Spring context). Includes {@link TestClockConfig}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestClockConfig.class)
public class SecurityProbes {

  /**
   * Registered as a member class of this configuration (only where it is imported); {@code @TestComponent} keeps
   * component scanning from adding it to every other test context.
   */
  @TestComponent
  @RestController
  static class Probes {

    private static Map<String, Object> who(String route) {
      PlayerPrincipal p = CurrentPlayer.require();
      return Map.of("route", route, "handle", p.handle(), "authMethod", p.authMethod().name());
    }

    @GetMapping("/api/v1/admin/__probe")
    Map<String, Object> adminRead() {
      return who("admin");
    }

    @PostMapping("/api/v1/admin/__probe")
    Map<String, Object> adminWrite() {
      return who("admin-write");
    }

    @GetMapping("/api/v1/market/__probe")
    Map<String, Object> marketRead() {
      return who("market:read");
    }

    @PostMapping("/api/v1/orders")
    Map<String, Object> placeOrder() {
      return who("trade");
    }

    @GetMapping("/api/v1/orders")
    Map<String, Object> listOrders() {
      return who("account:read");
    }

    @GetMapping("/api/v1/players/__probe")
    Map<String, Object> leagueRead() {
      return who("league:read");
    }

    @GetMapping("/api/v1/me/__probe")
    Map<String, Object> sessionOnly() {
      return who("session-only");
    }
  }
}
