package com.stocks.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The production fail-fast of §23.2: with profile {@code prod}, startup stops (an {@link IllegalStateException} from
 * {@link #afterSingletonsInstantiated()}) when
 * <ul>
 *   <li>{@code APP_PUBLIC_URL} is missing or not {@code https://…};</li>
 *   <li>{@code API_KEY_PEPPER} is under 32 bytes (base64-decoded when it is base64, else its UTF-8 bytes);</li>
 *   <li>{@code ENGINE_SEED} is missing or the dev default;</li>
 *   <li>the market-data profile is {@code simulated} without {@code stocks.marketdata.allow-simulated-in-prod}, or
 *       {@code hybrid}/{@code alpaca-sip} without Alpaca keys;</li>
 *   <li>the {@code test}, {@code e2e} or {@code pgit} profile is active too, or a {@code TestClockController} bean
 *       exists (§18.5).</li>
 * </ul>
 * "No admin and no bootstrap variables" needs the database and is checked by {@code identity.BootstrapAdminRunner}.
 * The checks read WS2/WS3 keys through the {@link Environment} only, so this class depends on no feature package.
 */
@Component
@Profile("prod")
public class ProdSafetyCheck implements SmartInitializingSingleton {

  private final Environment env;
  private final ApplicationContext context;

  public ProdSafetyCheck(Environment env, ApplicationContext context) {
    this.env = env;
    this.context = context;
  }

  @Override
  public void afterSingletonsInstantiated() {
    List<String> problems = problems(env, Arrays.asList(context.getBeanDefinitionNames()).stream()
        .anyMatch(name -> {
          Class<?> type = context.getType(name, false);
          return type != null && type.getSimpleName().equals("TestClockController");
        }));
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Refusing to start in prod (§23.2):\n - " + String.join("\n - ", problems));
    }
  }

  /** Every failed check, as a message; empty when production is safe to start. */
  static List<String> problems(Environment env, boolean testClockControllerPresent) {
    List<String> out = new ArrayList<>();
    String publicUrl = prop(env, "stocks.public-url");
    if (!publicUrl.startsWith("https://") || publicUrl.length() <= "https://".length()) {
      out.add("APP_PUBLIC_URL must be the https:// URL of the site (got '" + publicUrl + "')");
    }
    String pepper = prop(env, "stocks.security.api-key-pepper");
    if (pepperBytes(pepper) < 32) {
      out.add("API_KEY_PEPPER must be at least 32 bytes (openssl rand -base64 48)");
    }
    String seed = prop(env, "stocks.engine.seed");
    if (seed.isBlank() || seed.equals("dev-seed-change-me")) {
      out.add("ENGINE_SEED must be set to a secret value");
    }
    String md = prop(env, "stocks.marketdata.profile").trim().toLowerCase(Locale.ROOT);
    boolean allowSim = Boolean.parseBoolean(prop(env, "stocks.marketdata.allow-simulated-in-prod"));
    if (md.isEmpty() || (md.equals("simulated") && !allowSim)) {
      out.add("MARKETDATA_PROFILE must be hybrid or alpaca-sip (simulated needs "
          + "stocks.marketdata.allow-simulated-in-prod=true)");
    }
    if ((md.equals("hybrid") || md.equals("alpaca-sip"))
        && (prop(env, "stocks.marketdata.alpaca.key-id").isBlank()
            || prop(env, "stocks.marketdata.alpaca.secret-key").isBlank())) {
      out.add("MARKETDATA_PROFILE=" + md + " needs ALPACA_KEY_ID and ALPACA_SECRET_KEY");
    }
    for (String p : env.getActiveProfiles()) {
      if (p.equals("test") || p.equals("e2e") || p.equals("pgit")) {
        out.add("profile '" + p + "' must not be active with prod");
      }
    }
    if (testClockControllerPresent) {
      out.add("TestClockController must not exist in prod");
    }
    return out;
  }

  /** The property, or "" when it is missing or its placeholder cannot be resolved (prod has no defaults). */
  static String prop(Environment env, String key) {
    try {
      String v = env.getProperty(key);
      return v == null ? "" : v;
    } catch (IllegalArgumentException unresolvable) {
      return "";
    }
  }

  static int pepperBytes(String pepper) {
    if (pepper == null || pepper.isBlank()) {
      return 0;
    }
    try {
      return Base64.getDecoder().decode(pepper.trim()).length;
    } catch (IllegalArgumentException notBase64) {
      return pepper.getBytes(StandardCharsets.UTF_8).length;
    }
  }
}
