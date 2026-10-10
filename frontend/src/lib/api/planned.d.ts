/**
 * PLANNED API: hand-written §18.3 shapes for the endpoints whose controllers have not landed yet (Phase 0/1).
 *
 * `schema.d.ts` is GENERATED (`make openapi` → `npm run gen:api`) and CI diffs it, so it only covers the
 * controllers that exist. `contract.ts` merges the two: for every path and method the generated operation wins,
 * and this file fills in the rest; the same holds for component schemas. When a controller lands and
 * `make openapi` publishes its operations and DTOs, delete the matching entries here (they are already ignored).
 *
 * Shapes follow ARCHITECTURE §18.3. Note that the generated schemas follow the §18 OpenAPI rule 2: every
 * non-primitive response field is `T | null`, so code that moves from a planned type to a generated one must
 * handle `null` (the entries below are stricter than what will be generated).
 */
import type { components as generated } from './schema'

export interface paths {
    "/api/v1/me": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get?: never; // generated
        put?: never; post?: never; delete?: never; options?: never; head?: never;
        patch: operations["updateMe"];
        trace?: never;
    };
    "/api/v1/market/clock": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getClock"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/market/indices": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getIndices"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/market/movers": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getMovers"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/assets/search": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["searchAssets"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/assets/{symbol}": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getAsset"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/quotes": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getQuotes"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/bars/{symbol}": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getBars"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/account": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getAccount"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/account/positions": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getPositions"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/orders": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["listOrders"];
        put?: never;
        post: operations["placeOrder"];
        delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/orders/preview": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get?: never; put?: never;
        post: operations["previewOrder"];
        delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/orders/{orderId}": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getOrder"];
        put?: never; post?: never;
        delete: operations["cancelOrder"];
        options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/watchlists": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["listWatchlists"];
        put?: never;
        post: operations["createWatchlist"];
        delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/watchlists/{id}/symbols": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get?: never; put?: never;
        post: operations["addWatchlistSymbol"];
        delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
    "/api/v1/leaderboard": {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        get: operations["getLeaderboard"];
        put?: never; post?: never; delete?: never; options?: never; head?: never; patch?: never; trace?: never;
    };
}

export type webhooks = Record<string, never>;

export interface components {
    schemas: {
        Warning: { code: string; message: string };
        Fees: { sec: string; taf: string; cat: string; total: string };
        UpdateMeRequest: {
            displayName?: string | null;
            preferences?: Partial<generated["schemas"]["Preferences"]> | null;
        };
        Clock: {
            now: string;
            nowEt: string;
            /** @enum {string} */
            session: "CLOSED" | "PRE" | "REGULAR" | "POST";
            tradingDate: string | null;
            sessionEndsAt: string | null;
            /** @enum {string} */
            nextSession: "CLOSED" | "PRE" | "REGULAR" | "POST";
            nextSessionAt: string;
            nextRegularOpen: string;
            nextRegularClose: string;
            earlyClose: boolean;
            upcomingHolidays: { date: string; name: string }[];
            upcomingEarlyCloses: string[];
            feed: {
                profile: string;
                /** @enum {string} */
                quality: "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
                /** @enum {string} */
                status: "LIVE" | "DEGRADED" | "DOWN";
                liveIexWindowEt: string | null;
            };
        };
        AssetSummary: {
            symbol: string;
            name: string;
            exchange: string | null;
            /** @enum {string} */
            type: "COMMON_STOCK" | "ETF" | "ADR" | "OTHER" | "UNKNOWN";
            tradable: boolean;
            fractionable: boolean;
        };
        Fundamentals: {
            marketCap: string | null;
            peTtm: string | null;
            epsTtm: string | null;
            dividendYield: string | null;
            beta: string | null;
            sharesOutstanding: string | null;
            industry: string | null;
            website: string | null;
            source: string;
        };
        Asset: components["schemas"]["AssetSummary"] & {
            shortable: boolean;
            marginable: boolean;
            /** @enum {string} */
            liquidityTier: "MEGA" | "MID" | "SMALL";
            /** @enum {string} */
            status: "ACTIVE" | "INACTIVE";
            week52High: string | null;
            week52Low: string | null;
            logoUrl: string;
            fundamentals: components["schemas"]["Fundamentals"] | null;
        };
        ExtendedLine: {
            /** @enum {string} */
            session: "PRE" | "POST";
            price: string;
            change: string;
            changePct: string;
            at: string;
        };
        Quote: {
            symbol: string;
            bid: string | null;
            bidSize: number | null;
            ask: string | null;
            askSize: number | null;
            last: string | null;
            lastSize: number | null;
            lastAt: string | null;
            prevClose: string | null;
            change: string | null;
            changePct: string | null;
            dayOpen: string | null;
            dayHigh: string | null;
            dayLow: string | null;
            volume: number | null;
            volumeIsPartial: boolean;
            extended: components["schemas"]["ExtendedLine"] | null;
            asOf: string | null;
            ageMs: number | null;
            /** @enum {string} */
            quality: "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
            source: string;
            halted: boolean;
            /** @enum {string} */
            session: "CLOSED" | "PRE" | "REGULAR" | "POST";
        };
        QuotesResponse: { quotes: components["schemas"]["Quote"][] };
        Bar: { t: string; o: string; h: string; l: string; c: string; v: number; vw: string | null };
        Bars: {
            symbol: string;
            timeframe: string;
            adjustment: string;
            bars: components["schemas"]["Bar"][];
            tailFrom: string | null;
            source: string;
        };
        Movers: {
            /** @enum {string} */
            list: "GAINERS" | "LOSERS" | "ACTIVES";
            asOf: string;
            source: string;
            items: { symbol: string; name: string | null; price: string; change: string; changePct: string; volume: number | null }[];
        };
        Account: {
            accountId: string;
            runNo: number;
            /** @enum {string} */
            type: "CASH";
            /** @enum {string} */
            status: "ACTIVE" | "CLOSED";
            /** @enum {string} */
            restriction: "NONE" | "SETTLED_CASH_ONLY" | "LIQUIDATION_ONLY" | "FROZEN";
            restrictionUntil: string | null;
            restrictionReason: string | null;
            equity: string;
            longMarketValue: string;
            dividendsReceivable: string;
            cash: { total: string; settled: string; unsettled: string; settledAvailable: string; withdrawable: string };
            openBuyHolds: string;
            buyingPower: string;
            startingCash: string;
            netContributions: string;
            totalPnl: string;
            totalReturn: string;
            unitValue: string;
            dayPnl: string;
            dayReturn: string;
            gfv: { active: number; nextExpiry: string | null; settledCashOnlyAt: number; liquidationOnlyAt: number };
            dayTradesLast5Days: number;
            openedAt: string;
            /** @enum {string} */
            marksQuality: "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
            asOf: string;
        };
        Position: {
            symbol: string;
            name: string;
            qty: string;
            availableQty: string;
            avgCost: string;
            costBasis: string;
            last: string | null;
            mark: string;
            /** @enum {string} */
            markQuality: "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
            marketValue: string;
            unrealizedPnl: string;
            unrealizedReturn: string;
            dayPnl: string;
            dayReturn: string | null;
            weight: string;
            fractionable: boolean;
            openedAt: string;
        };
        Execution: {
            executionId: string;
            orderId: string;
            symbol: string;
            /** @enum {string} */
            side: "BUY" | "SELL";
            qty: string;
            price: string;
            principal: string;
            fees: components["schemas"]["Fees"];
            netAmount: string;
            realizedPnl: string | null;
            /** @enum {string} */
            liquidity: "TOUCH" | "WALK" | "RESTING" | "PRINT" | "OPEN";
            executedAt: string;
            tradeDate: string;
            settleDate: string;
            refQuote: {
                bid: string | null;
                ask: string | null;
                bidSize: number | null;
                askSize: number | null;
                last: string | null;
                quoteAt: string | null;
                confirmedAt: string | null;
                source: string;
                /** @enum {string} */
                quality: "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
                clamped: boolean;
            };
        };
        OrderEvent: {
            seq: number;
            /** @enum {string} */
            type: "ACCEPTED" | "QUEUED" | "WORKING" | "PAUSED" | "RESUMED" | "TRIGGERED" | "TRAIL_UPDATED" | "AWAITING_QUOTE" | "BEYOND_COLLAR" | "PARTIAL_FILL" | "FILL" | "CANCELLED" | "EXPIRED" | "REPLACED" | "HOLD_ADJUSTED";
            /** @enum {string} */
            statusAfter: "PENDING" | "WORKING" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED" | "EXPIRED" | "REPLACED" | "REJECTED";
            at: string;
            detail: Record<string, unknown> | null;
        };
        Order: {
            orderId: string;
            clientOrderId: string;
            accountId: string;
            symbol: string;
            /** @enum {string} */
            side: "BUY" | "SELL";
            /** @enum {string} */
            type: "MARKET" | "LIMIT" | "STOP" | "STOP_LIMIT" | "TRAILING_STOP";
            qty: string | null;
            notional: string | null;
            fractional: boolean;
            limitPrice: string | null;
            stopPrice: string | null;
            trailAmount: string | null;
            trailPercent: string | null;
            watermarkPrice: string | null;
            collarPrice: string | null;
            /** @enum {string} */
            timeInForce: "DAY" | "GTC";
            extendedHours: boolean;
            /** @enum {string} */
            status: "PENDING" | "WORKING" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED" | "EXPIRED" | "REPLACED" | "REJECTED";
            /** @enum {string|null} */
            waitReason: "LATENCY" | "QUEUED_FOR_OPEN" | "QUEUED_FOR_EXT_OPEN" | "SESSION_CLOSED" | "NOT_TRIGGERED" | "AWAITING_QUOTE" | "HALTED" | "CORPORATE_ACTION_HOLD" | "BEYOND_COLLAR" | "LIMIT_NOT_REACHED" | null;
            statusReason: string | null;
            triggered: boolean;
            filledQty: string;
            filledPrincipal: string;
            avgFillPrice: string | null;
            fees: string;
            reservedCash: string;
            reservedQty: string;
            sessionDate: string;
            receivedAt: string;
            acceptedAt: string;
            eligibleAt: string;
            activatedAt: string | null;
            triggeredAt: string | null;
            expiresAt: string;
            doneAt: string | null;
            replacesOrderId: string | null;
            replacedByOrderId: string | null;
            /** @enum {string} */
            channel: "WEB" | "REST_KEY" | "MCP_KEY" | "MCP_OAUTH" | "SYSTEM" | "ADMIN";
            rationale: string | null;
            executions?: components["schemas"]["Execution"][];
            events?: components["schemas"]["OrderEvent"][];
        };
        PageOrder: { items: components["schemas"]["Order"][]; nextCursor: string | null };
        PlaceOrderRequest: {
            clientOrderId?: string | null;
            symbol: string;
            /** @enum {string} */
            side: "BUY" | "SELL";
            /** @enum {string} */
            type: "MARKET" | "LIMIT" | "STOP" | "STOP_LIMIT" | "TRAILING_STOP";
            qty?: string | null;
            notional?: string | null;
            limitPrice?: string | null;
            stopPrice?: string | null;
            trailAmount?: string | null;
            trailPercent?: string | null;
            /** @enum {string} */
            timeInForce?: "DAY" | "GTC" | null;
            extendedHours?: boolean | null;
            rationale?: string | null;
            shareNote?: boolean | null;
            acknowledge?: "GFV_RISK"[] | null;
        };
        Estimate: {
            price: string | null;
            qty: string | null;
            principal: string | null;
            fees: components["schemas"]["Fees"];
            hold: string;
            quoteAsOf: string | null;
            /** @enum {string} */
            quoteQuality: "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
        };
        PlaceOrderResponse: {
            order: components["schemas"]["Order"];
            estimate: components["schemas"]["Estimate"];
            buyingPowerBefore: string;
            buyingPowerAfter: string;
            warnings: components["schemas"]["Warning"][];
            replayed: boolean;
        };
        Preview: {
            valid: boolean;
            errors: { code: string; field: string | null; message: string; hint: string | null }[];
            warnings: components["schemas"]["Warning"][];
            estimate: components["schemas"]["Estimate"] | null;
            buyingPowerBefore: string;
            buyingPowerAfter: string | null;
            sessionNote: string | null;
        };
        Watchlist: { id: string; name: string; sortOrder: number; symbols: string[] };
        CreateWatchlistRequest: { name: string; symbols?: string[] | null };
        AddSymbolRequest: { symbol: string };
        PlayerSummary: {
            playerId: string;
            handle: string;
            displayName: string;
            /** @enum {string} */
            kind: "HUMAN" | "AI";
            aiModel: string | null;
            avatarSeed: string | null;
            memberSince: string;
            resets: number;
        };
        LeaderboardRow: {
            rank: number | null;
            rankChange: number | null;
            eligible: boolean;
            eligibilityNote: string | null;
            partialPeriod: boolean;
            rebased: boolean;
            player: components["schemas"]["PlayerSummary"];
            apiTrader: boolean;
            return: string;
            pnl: string;
            excessVsBenchmark: string | null;
            maxDrawdown: string | null;
            equity: string | null;
            netContributions: string;
            flowsInPeriod: string;
            fills: number;
            symbolsTraded: number;
            winRate: string | null;
            topHolding: string | null;
            spark: string[];
        };
        Leaderboard: {
            /** @enum {string} */
            period: "TODAY" | "WEEK" | "MONTH" | "YTD" | "SEASON" | "ALL";
            /** @enum {string} */
            metric: "RETURN" | "PNL";
            /** @enum {string} */
            kind: "ALL" | "HUMAN" | "AI";
            periodStart: string;
            asOf: string;
            benchmark: { symbol: string; return: string | null };
            arena: { humansMedianReturn: string | null; aiMedianReturn: string | null };
            rows: components["schemas"]["LeaderboardRow"][];
            me: components["schemas"]["LeaderboardRow"] | null;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}

export type $defs = Record<string, never>;

type Problem = { content: { "application/problem+json": generated["schemas"]["Problem"] } };
type Json<T> = { headers: { [name: string]: unknown }; content: { "application/json": T } };
type NoContent = { headers: { [name: string]: unknown }; content?: never };

export interface operations {
    updateMe: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody: { content: { "application/json": components["schemas"]["UpdateMeRequest"] } };
        responses: { 200: Json<generated["schemas"]["Me"]>; 400: Problem };
    };
    getClock: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Clock"]> };
    };
    getIndices: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Quote"][]> };
    };
    getMovers: {
        parameters: { query?: { list?: "GAINERS" | "LOSERS" | "ACTIVES"; limit?: number }; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Movers"]> };
    };
    searchAssets: {
        parameters: { query: { q: string; limit?: number }; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["AssetSummary"][]> };
    };
    getAsset: {
        parameters: { query?: never; header?: never; path: { symbol: string }; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Asset"]>; 404: Problem };
    };
    getQuotes: {
        parameters: { query: { symbols: string }; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["QuotesResponse"]> };
    };
    getBars: {
        parameters: {
            query?: {
                range?: "1D" | "5D" | "1M" | "3M" | "6M" | "YTD" | "1Y" | "5Y" | "ALL";
                timeframe?: "1Min" | "5Min" | "15Min" | "30Min" | "1Hour" | "1Day" | "1Week" | "1Month";
                start?: string;
                end?: string;
                limit?: number;
                adjustment?: "RAW" | "SPLIT" | "ALL";
                extendedHours?: boolean;
            };
            header?: never;
            path: { symbol: string };
            cookie?: never;
        };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Bars"]>; 404: Problem };
    };
    getAccount: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Account"]> };
    };
    getPositions: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Position"][]> };
    };
    listOrders: {
        parameters: {
            query?: { status?: "OPEN" | "CLOSED" | "ALL"; symbol?: string; from?: string; to?: string; cursor?: string; limit?: number };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["PageOrder"]> };
    };
    placeOrder: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody: { content: { "application/json": components["schemas"]["PlaceOrderRequest"] } };
        responses: {
            200: Json<components["schemas"]["PlaceOrderResponse"]>;
            201: Json<components["schemas"]["PlaceOrderResponse"]>;
            409: Problem;
            422: Problem;
        };
    };
    previewOrder: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody: { content: { "application/json": components["schemas"]["PlaceOrderRequest"] } };
        responses: { 200: Json<components["schemas"]["Preview"]> };
    };
    getOrder: {
        parameters: { query?: never; header?: never; path: { orderId: string }; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Order"]>; 404: Problem };
    };
    cancelOrder: {
        parameters: { query?: never; header?: never; path: { orderId: string }; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Order"]>; 404: Problem; 409: Problem };
    };
    listWatchlists: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Watchlist"][]> };
    };
    createWatchlist: {
        parameters: { query?: never; header?: never; path?: never; cookie?: never };
        requestBody: { content: { "application/json": components["schemas"]["CreateWatchlistRequest"] } };
        responses: { 201: Json<components["schemas"]["Watchlist"]>; 400: Problem };
    };
    addWatchlistSymbol: {
        parameters: { query?: never; header?: never; path: { id: string }; cookie?: never };
        requestBody: { content: { "application/json": components["schemas"]["AddSymbolRequest"] } };
        responses: { 200: Json<components["schemas"]["Watchlist"]>; 404: Problem };
    };
    getLeaderboard: {
        parameters: {
            query?: {
                period?: "TODAY" | "WEEK" | "MONTH" | "YTD" | "SEASON" | "ALL";
                metric?: "RETURN" | "PNL";
                kind?: "ALL" | "HUMAN" | "AI";
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: { 200: Json<components["schemas"]["Leaderboard"]> };
    };
}
