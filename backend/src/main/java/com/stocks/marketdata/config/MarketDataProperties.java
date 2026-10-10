package com.stocks.marketdata.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code stocks.marketdata} (§15.4, §23.1; owner WS2). Every key has the §23.1 default, so a context that sets nothing
 * (unit tests, tools) still binds a usable SIM configuration.
 *
 * @param profile               {@code simulated} | {@code hybrid} | {@code alpaca-sip}
 * @param allowSimulatedInProd  prod refuses SIM unless this is true (§15.9, checked by WS1's {@code ProdSafetyCheck})
 * @param pinned                symbols always leased at {@code PINNED} (the SPY benchmark)
 * @param routes                per-capability provider order overrides (§15.4); empty = the profile defaults
 */
@ConfigurationProperties("stocks.marketdata")
public record MarketDataProperties(
    @DefaultValue("simulated") String profile,
    @DefaultValue("false") boolean allowSimulatedInProd,
    @DefaultValue("SPY") List<String> pinned,
    Map<String, List<String>> routes,
    @DefaultValue Poll poll,
    @DefaultValue Alpaca alpaca,
    @DefaultValue Finnhub finnhub,
    @DefaultValue Sim sim,
    @DefaultValue Screener screener) {

  public static final String SIMULATED = "simulated";
  public static final String HYBRID = "hybrid";
  public static final String ALPACA_SIP = "alpaca-sip";

  public MarketDataProperties {
    profile = profile == null || profile.isBlank() ? SIMULATED : profile.trim().toLowerCase(Locale.ROOT);
    pinned = pinned == null ? List.of() : pinned.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
    routes = routes == null ? Map.of() : Map.copyOf(routes);
    screener = screener == null ? Screener.defaults() : screener;
  }

  /** Defaults everywhere: the SIM profile with the §23.1 values (unit tests, tools). */
  public static MarketDataProperties defaults() {
    return new MarketDataProperties(SIMULATED, false, List.of("SPY"), Map.of(), Poll.defaults(), Alpaca.defaults(),
        Finnhub.defaults(), Sim.defaults(), Screener.defaults());
  }

  public boolean simulated() {
    return SIMULATED.equals(profile);
  }

  /** {@code hybrid} or {@code alpaca-sip}: live Alpaca data (plus Finnhub when a token exists). */
  public boolean live() {
    return HYBRID.equals(profile) || ALPACA_SIP.equals(profile);
  }

  /** Alpaca's feed for quotes and the stream: {@code sip} on {@code alpaca-sip}, else the configured feed. */
  public String effectiveFeed() {
    return ALPACA_SIP.equals(profile) ? "sip" : alpaca.feed();
  }

  /** Poll cadences of {@code SnapshotPoller} (§15.5). */
  public record Poll(@DefaultValue("2s") Duration hot, @DefaultValue("10s") Duration warm,
                     @DefaultValue("60s") Duration universe) {
    public static Poll defaults() {
      return new Poll(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(60));
    }
  }

  /** Alpaca market data (§15.6). Keys live only on the server (env vars); they never reach clients. */
  public record Alpaca(String keyId, String secretKey,
                       @DefaultValue("iex") String feed,
                       @DefaultValue("https://data.alpaca.markets") String dataUrl,
                       @DefaultValue("https://paper-api.alpaca.markets") String tradingUrl,
                       @DefaultValue("wss://stream.data.alpaca.markets/v2/iex") String streamUrl,
                       @DefaultValue("30") int maxStreamSymbols,
                       @DefaultValue("180") int restPerMinute,
                       @DefaultValue("100") int snapshotBatch,
                       @DefaultValue("5s") Duration connectTimeout,
                       @DefaultValue("10s") Duration readTimeout) {

    public Alpaca {
      feed = feed == null || feed.isBlank() ? "iex" : feed.trim().toLowerCase(Locale.ROOT);
      keyId = keyId == null ? "" : keyId.trim();
      secretKey = secretKey == null ? "" : secretKey.trim();
    }

    public static Alpaca defaults() {
      return new Alpaca("", "", "iex", "https://data.alpaca.markets", "https://paper-api.alpaca.markets",
          "wss://stream.data.alpaca.markets/v2/iex", 30, 180, 100, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    public boolean hasKeys() {
      return !keyId.isEmpty() && !secretKey.isEmpty();
    }

    @Override
    public String toString() {                     // never log the secret
      return "Alpaca[feed=" + feed + ", dataUrl=" + dataUrl + ", streamUrl=" + streamUrl + ", keys="
          + (hasKeys() ? "set" : "missing") + "]";
    }
  }

  /** Finnhub (§15.6). A blank token drops Finnhub from every route (§15.4). */
  public record Finnhub(String token,
                        @DefaultValue("https://finnhub.io/api/v1") String baseUrl,
                        @DefaultValue("wss://ws.finnhub.io") String wsUrl,
                        @DefaultValue("50") int maxStreamSymbols,
                        @DefaultValue("55") int restPerMinute,
                        @DefaultValue("true") boolean streamEnabled) {

    public Finnhub {
      token = token == null ? "" : token.trim();
    }

    public static Finnhub defaults() {
      return new Finnhub("", "https://finnhub.io/api/v1", "wss://ws.finnhub.io", 50, 55, true);
    }

    public boolean hasToken() {
      return !token.isEmpty();
    }

    @Override
    public String toString() {
      return "Finnhub[baseUrl=" + baseUrl + ", token=" + (hasToken() ? "set" : "missing") + "]";
    }
  }

  /**
   * The SIM provider (§15.7).
   *
   * @param clock      {@code wall} | {@code offset} | {@code fixed} | {@code speed}
   * @param offset     {@code offset} mode: wall time plus this (ignored when {@code anchor} is set)
   * @param anchor     {@code offset}/{@code fixed}/{@code speed}: "now" at boot
   * @param speed      {@code speed} mode: simulated seconds per wall second
   * @param alwaysOpen dev/e2e only: {@code AlwaysOpenCalendar} (§9.3); prod refuses it
   * @param scenarios  names of {@code marketdata/sim/scenarios/<name>.yml} to activate
   */
  public record Sim(@DefaultValue("42") long seed,
                    @DefaultValue("wall") String clock,
                    @DefaultValue("P0D") Duration offset,
                    Instant anchor,
                    @DefaultValue("1.0") String speed,
                    @DefaultValue("false") boolean alwaysOpen,
                    @DefaultValue List<String> scenarios) {

    public Sim {
      clock = clock == null || clock.isBlank() ? "wall" : clock.trim().toLowerCase(Locale.ROOT);
      offset = offset == null ? Duration.ZERO : offset;
      speed = speed == null || speed.isBlank() ? "1.0" : speed.trim();
      scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    public static Sim defaults() {
      return new Sim(42, "wall", Duration.ZERO, null, "1.0", false, List.of());
    }
  }

  /**
   * The screener's statistics (§15.10, Phase 2).
   *
   * @param profilesPerMinute company profiles fetched per minute by {@code SCREENER_STATS} (Finnhub {@code profile2},
   *                          {@code BACKGROUND} priority); 0 turns profiles (and so market caps) off
   */
  public record Screener(@DefaultValue("3") int profilesPerMinute) {

    public Screener {
      profilesPerMinute = Math.max(0, Math.min(30, profilesPerMinute));
    }

    public static Screener defaults() {
      return new Screener(3);
    }
  }
}
