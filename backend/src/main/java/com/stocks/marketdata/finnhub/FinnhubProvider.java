package com.stocks.marketdata.finnhub;

import com.stocks.common.domain.AssetType;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.MarketDataProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Finnhub free (§15.1): fundamentals, security types, search, company news (fallback) and the US SIP last-trade
 * stream (about 50 symbols). Only present when {@code FINNHUB_TOKEN} is set (§15.4).
 */
public class FinnhubProvider implements MarketDataProvider {

  static final Capabilities CAPABILITIES = new Capabilities(true, false, false, 50, 55, Duration.ZERO, null, null,
      false, true, true, false, false, false, false, false);

  private final MarketDataProperties.Finnhub props;
  private final FinnhubRestClient rest;
  private final TimeSource time;
  private final WebSocketStream.Timings timings;

  public FinnhubProvider(MarketDataProperties.Finnhub props, FinnhubRestClient rest, TimeSource time,
                         WebSocketStream.Timings timings) {
    this.props = props;
    this.rest = rest;
    this.time = time;
    this.timings = timings;
  }

  public FinnhubRestClient rest() {
    return rest;
  }

  @Override
  public String id() {
    return FinnhubRestClient.ID;
  }

  @Override
  public Capabilities capabilities() {
    return new Capabilities(true, false, false, props.maxStreamSymbols(), props.restPerMinute(), Duration.ZERO, null,
        null, false, true, true, false, false, false, false, false);
  }

  @Override
  public Optional<Fundamentals> fundamentals(String symbol) {
    return rest.fundamentals(symbol, RefreshPriority.UI);
  }

  @Override
  public Map<String, AssetType> assetTypes() {
    return rest.assetTypes(RefreshPriority.BACKGROUND);
  }

  /** {@code company-news} for up to three symbols over the last week, newest first. */
  @Override
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    Instant end = before == null ? time.now() : before;
    LocalDate to = Et.date(end);
    List<NewsItem> out = new ArrayList<>();
    symbols.stream().sorted().limit(3).forEach(s ->
        out.addAll(rest.companyNews(s, to.minusDays(7), to, RefreshPriority.UI)));
    return out.stream().filter(n -> n.publishedAt() == null || !n.publishedAt().isAfter(end))
        .sorted(Comparator.comparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(Math.max(0, limit)).toList();
  }

  @Override
  public FinnhubStreamClient openStream(Consumer<MarketEvent> sink) {
    return openStream(sink, () -> true);
  }

  public FinnhubStreamClient openStream(Consumer<MarketEvent> sink, BooleanSupplier venueHours) {
    String sep = props.wsUrl().contains("?") ? "&" : "?";
    URI uri = URI.create(props.wsUrl() + sep + "token=" + URLEncoder.encode(props.token(), StandardCharsets.UTF_8));
    FinnhubStreamClient stream = new FinnhubStreamClient(uri, time, timings, venueHours, sink);
    stream.start();
    return stream;
  }
}
