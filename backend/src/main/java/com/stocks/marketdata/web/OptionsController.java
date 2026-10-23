package com.stocks.marketdata.web;

import com.stocks.common.error.ApiException;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.OptionChainDto;
import com.stocks.contract.marketdata.dto.OptionExpirationsDto;
import com.stocks.marketdata.mapper.OptionDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §30 option chains ({@code market:read}): expirations and one expiration's calls and puts. */
@RestController
@RequestMapping("/api/v1/options")
@Tag(name = "options", description = "Option chains of US stocks and ETFs (§30)")
public class OptionsController {

  private final MarketDataService marketData;
  private final SettingsService settings;

  public OptionsController(MarketDataService marketData, SettingsService settings) {
    this.marketData = marketData;
    this.settings = settings;
  }

  @GetMapping("/{underlying}/expirations")
  @Operation(summary = "Listed option expirations of a stock or ETF, nearest first",
      description = "feed: indicative (Alpaca's free derived quotes, not OPRA), opra or simulated. enabled: buying "
          + "options is allowed. Errors: SYMBOL_NOT_FOUND, PROVIDER_UNAVAILABLE.")
  public OptionExpirationsDto optionExpirations(@PathVariable String underlying) {
    String u = Symbols.normalize(underlying, "underlying");
    return OptionDtoMapper.toDto(marketData.optionExpirations(u), enabled());
  }

  @GetMapping("/{underlying}/chain")
  @Operation(summary = "One expiration's option chain: calls and puts by strike, with quotes",
      description = "Prices are per share; one contract is 100 shares. Trade a contract by its OCC symbol "
          + "(e.g. AAPL261120C00250000) with POST /orders: BUY to open, SELL to close, whole contracts, MARKET or "
          + "LIMIT, DAY, 09:30-16:00 ET. Errors: SYMBOL_NOT_FOUND, VALIDATION_FAILED, PROVIDER_UNAVAILABLE.")
  public OptionChainDto optionChain(@PathVariable String underlying,
      @Parameter(description = "Expiration date, YYYY-MM-DD (one of /expirations)") @RequestParam String expiration) {
    String u = Symbols.normalize(underlying, "underlying");
    return OptionDtoMapper.toDto(marketData.optionChain(u, date(expiration)), enabled());
  }

  static LocalDate date(String raw) {
    try {
      return LocalDate.parse(raw == null ? "" : raw.trim());
    } catch (DateTimeParseException e) {
      throw ApiException.validation("expiration", "expiration must be a date, YYYY-MM-DD");
    }
  }

  private boolean enabled() {
    try {
      return settings.optionsEnabled();
    } catch (ApiException e) {
      return true;
    }
  }
}
