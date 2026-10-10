package com.stocks.contract.marketdata.model;

import com.stocks.common.domain.MoverList;
import java.time.Instant;
import java.util.List;

/** A movers list (Alpaca movers / most-actives, or computed from the universe). */
public record Movers(MoverList list, List<Mover> items, Instant asOf, String source) {}
