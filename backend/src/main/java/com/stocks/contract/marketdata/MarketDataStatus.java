package com.stocks.contract.marketdata;

import java.util.List;

/** Market-data health for {@code SystemStatus.marketData} (§21.1). */
public record MarketDataStatus(String profile, List<String> staleSymbols, List<String> corporateActionHolds,
                               List<ProviderStatus> providers) {}
