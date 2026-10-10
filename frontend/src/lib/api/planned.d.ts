/**
 * PLANNED API: hand-written §18.3 / §21.1 shapes for the endpoints whose controllers have not landed yet (Phase 1).
 *
 * `schema.d.ts` is GENERATED (`make openapi` → `npm run gen:api`) and CI diffs it, so it only covers the
 * controllers that exist. `contract.ts` merges the two: for every path and method the generated operation wins,
 * and this file fills in the rest; the same holds for component schemas. When a controller lands and
 * `make openapi` publishes its operations and DTOs, delete the matching entries here (they are already ignored).
 *
 * These shapes are transcribed from the Day-0 Java records (`com.stocks.contract.*.dto`) under the §18 OpenAPI
 * rules, so they match what springdoc will generate:
 *  - schema names drop the Java `Dto` suffix, nested records keep their simple name (`AccountCashDto` → `AccountCash`),
 *    `Page<OrderDto>` becomes `PageOrder`;
 *  - RESPONSE records: every member is present; every non-primitive member is `T | null` (Java `int`/`long`/
 *    `boolean` stay `number`/`boolean`); enums are inline string unions plus `null`;
 *  - REQUEST records (`…Request`): members are optional and nullable unless annotated `@NotNull`/`@NotBlank`/
 *    `@Schema(requiredMode = REQUIRED)`, which makes them required (still `| null`).
 * The UI therefore handles `null` everywhere already, and regenerating `schema.d.ts` does not break the build.
 */
import type { components as generated } from './schema'

// ------------------------------------------------------------------------------------------------ enums (§7.1)

type DataQuality = "REALTIME_CONSOLIDATED" | "REALTIME_SINGLE_VENUE" | "DELAYED_15M" | "SYNTHETIC" | "SIMULATED" | "STALE" | "NONE";
type SessionType = "CLOSED" | "PRE" | "REGULAR" | "POST";
type PlayerKind = "HUMAN" | "AI";
type PlayerRole = "PLAYER" | "ADMIN";
type PlayerStatus = "ACTIVE" | "DISABLED" | "FROZEN";
type Channel = "WEB" | "REST_KEY" | "MCP_KEY" | "MCP_OAUTH" | "SYSTEM" | "ADMIN";
type AccountType = "CASH" | "MARGIN";
type AccountStatus = "ACTIVE" | "CLOSED";
type AccountRestriction = "NONE" | "SETTLED_CASH_ONLY" | "LIQUIDATION_ONLY" | "FROZEN";
type Side = "BUY" | "SELL";
type OrderType = "MARKET" | "LIMIT" | "STOP" | "STOP_LIMIT" | "TRAILING_STOP";
type TimeInForce = "DAY" | "GTC";
type OrderStatus = "PENDING" | "WORKING" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED" | "EXPIRED" | "REPLACED" | "REJECTED";
type WaitReason = "LATENCY" | "QUEUED_FOR_OPEN" | "QUEUED_FOR_EXT_OPEN" | "SESSION_CLOSED" | "NOT_TRIGGERED" | "AWAITING_QUOTE" | "HALTED" | "CORPORATE_ACTION_HOLD" | "BEYOND_COLLAR" | "LIMIT_NOT_REACHED";
type OrderEventType = "ACCEPTED" | "QUEUED" | "WORKING" | "PAUSED" | "RESUMED" | "TRIGGERED" | "TRAIL_UPDATED" | "AWAITING_QUOTE" | "BEYOND_COLLAR" | "PARTIAL_FILL" | "FILL" | "CANCELLED" | "EXPIRED" | "REPLACED" | "HOLD_ADJUSTED";
type Liquidity = "TOUCH" | "WALK" | "RESTING" | "PRINT" | "OPEN";
type TransferType = "INITIAL" | "DEPOSIT" | "WITHDRAWAL" | "ADJUSTMENT";
type AdjustmentTreatment = "CONTRIBUTION" | "PNL_CORRECTION";
type TransferScope = "SINGLE" | "ALL" | "SYSTEM";
type TransferStatus = "APPLIED" | "SKIPPED";
type AssetType = "COMMON_STOCK" | "ETF" | "ADR" | "OTHER" | "UNKNOWN";
type LiquidityTier = "MEGA" | "MID" | "SMALL";
type ActivityType = "FILL" | "INITIAL_FUNDING" | "DEPOSIT" | "WITHDRAWAL" | "ADJUSTMENT" | "DIVIDEND" | "CASH_IN_LIEU" | "SPLIT" | "NAME_CHANGE" | "GFV" | "RESET" | "RESTRICTION" | "ADMIN_VIEW";
type LeaderboardPeriod = "TODAY" | "WEEK" | "MONTH" | "YTD" | "SEASON" | "ALL";
type LeaderboardMetric = "RETURN" | "PNL";
type KindFilter = "ALL" | "HUMAN" | "AI";
type HistoryRange = "1D" | "1W" | "1M" | "3M" | "YTD" | "1Y" | "ALL";
type ChartRange = "1D" | "5D" | "1M" | "3M" | "6M" | "YTD" | "1Y" | "5Y" | "ALL";
type Timeframe = "1Min" | "5Min" | "15Min" | "30Min" | "1Hour" | "1Day" | "1Week" | "1Month";
type AdjustmentMode = "RAW" | "SPLIT" | "ALL";
type MoverList = "GAINERS" | "LOSERS" | "ACTIVES";
type HoldingsVisibility = "EOD_WEIGHTS" | "EOD_FULL" | "HIDDEN";
type NotificationKind = "FILL" | "PARTIAL_FILL" | "ORDER_CANCELLED" | "ORDER_EXPIRED" | "DEPOSIT" | "WITHDRAWAL" | "ADJUSTMENT" | "GFV_ISSUED" | "RESTRICTION_CHANGED" | "CORPORATE_ACTION" | "ACCOUNT_RESET" | "SYSTEM";
type EngineState = "STARTING" | "RUNNING" | "HALTED";
type ViolationType = "GFV";
type JsonObject = { [key: string]: unknown };

type S = components["schemas"];

// ------------------------------------------------------------------------------------------------ operations

type HttpMethod = "get" | "put" | "post" | "delete" | "options" | "head" | "patch" | "trace";
type NoParams = { query?: never; header?: never; path?: never; cookie?: never };
type Query<Q> = { query?: Q; header?: never; path?: never; cookie?: never };
type RequiredQuery<Q> = { query: Q; header?: never; path?: never; cookie?: never };
type PathP<P, Q = never> = { query?: Q; header?: never; path: P; cookie?: never };

type Problem = { headers: { [name: string]: unknown }; content: { "application/problem+json": generated["schemas"]["Problem"] } };
type Json<T> = { headers: { [name: string]: unknown }; content: { "application/json": T } };
type NoContent = { headers: { [name: string]: unknown }; content?: never };
type Body<T> = { content: { "application/json": T } };

type Get<P, T> = { parameters: P; requestBody?: never; responses: { 200: Json<T>; default: Problem } };
type Send<P, B, T, Status extends 200 | 201 = 200> = {
    parameters: P;
    requestBody: Body<B>;
    responses: { [K in Status]: Json<T> } & { default: Problem };
};
type SendNoBody<P, T, Status extends 200 | 201 = 200> = {
    parameters: P;
    requestBody?: never;
    responses: { [K in Status]: Json<T> } & { default: Problem };
};
type SendNoContent<P, B> = { parameters: P; requestBody: Body<B>; responses: { 204: NoContent; default: Problem } };
type DeleteNoContent<P> = { parameters: P; requestBody?: never; responses: { 204: NoContent; default: Problem } };

/** One path item: the listed methods, every other method `?: never` (openapi-typescript's shape). */
type Item<M extends Partial<Record<HttpMethod, unknown>>, P = NoParams> = { parameters: P } & {
    [K in Exclude<HttpMethod, keyof M>]?: never;
} & M;

type Page<T> = { items: T[] | null; nextCursor: string | null };

export interface paths {
    // ---- §18.1 auth, me
    "/api/v1/auth/signup": Item<{ post: Send<NoParams, S["SignupRequest"], generated["schemas"]["Me"], 201> }>;
    "/api/v1/auth/reauth": Item<{ post: SendNoContent<NoParams, S["ReauthRequest"]> }>;
    "/api/v1/me": Item<{ patch: Send<NoParams, S["UpdateMeRequest"], generated["schemas"]["Me"]> }>;
    "/api/v1/me/password": Item<{ post: SendNoContent<NoParams, S["ChangePasswordRequest"]> }>;
    "/api/v1/me/api-keys": Item<{
        get: Get<NoParams, S["ApiKeyInfo"][]>;
        post: Send<NoParams, S["CreateApiKeyRequest"], S["ApiKeyCreated"], 201>;
    }>;
    "/api/v1/me/api-keys/{keyId}": Item<{ delete: DeleteNoContent<PathP<{ keyId: string }>> }>;
    "/api/v1/me/notifications": Item<{
        get: Get<Query<{ unreadOnly?: boolean; cursor?: string; limit?: number }>, S["PageNotification"]>;
    }>;
    "/api/v1/me/notifications/read": Item<{ post: SendNoContent<NoParams, S["MarkNotificationsReadRequest"]> }>;

    // ---- §18.2 market
    "/api/v1/market/clock": Item<{ get: Get<NoParams, S["Clock"]> }>;
    "/api/v1/market/indices": Item<{ get: Get<NoParams, S["Quote"][]> }>;
    "/api/v1/market/movers": Item<{ get: Get<Query<{ list?: MoverList; limit?: number }>, S["Movers"]> }>;
    "/api/v1/assets/search": Item<{ get: Get<RequiredQuery<{ q: string; limit?: number }>, S["AssetSummary"][]> }>;
    "/api/v1/assets/{symbol}": Item<{ get: Get<PathP<{ symbol: string }>, S["Asset"]> }>;
    "/api/v1/quotes": Item<{ get: Get<RequiredQuery<{ symbols: string }>, S["Quotes"]> }>;
    "/api/v1/bars/{symbol}": Item<{
        get: Get<
            PathP<
                { symbol: string },
                {
                    range?: ChartRange;
                    timeframe?: Timeframe;
                    start?: string;
                    end?: string;
                    limit?: number;
                    adjustment?: AdjustmentMode;
                    extendedHours?: boolean;
                }
            >,
            S["Bars"]
        >;
    }>;
    "/api/v1/news": Item<{ get: Get<Query<{ symbols?: string; limit?: number; before?: string }>, S["NewsList"]> }>;

    // ---- §18.2 account
    "/api/v1/account": Item<{ get: Get<NoParams, S["Account"]> }>;
    "/api/v1/account/positions": Item<{ get: Get<NoParams, S["Position"][]> }>;
    "/api/v1/account/positions/{symbol}": Item<{ get: Get<PathP<{ symbol: string }>, S["Position"]> }>;
    "/api/v1/account/positions/{symbol}/close": Item<{
        post: {
            parameters: PathP<{ symbol: string }>;
            requestBody: Body<S["ClosePositionRequest"]>;
            responses: { 200: Json<S["PlaceOrderResponse"]>; 201: Json<S["PlaceOrderResponse"]>; default: Problem };
        };
    }>;
    "/api/v1/account/activities": Item<{
        get: Get<Query<{ types?: string; from?: string; to?: string; cursor?: string; limit?: number }>, S["PageActivity"]>;
    }>;
    "/api/v1/account/history": Item<{ get: Get<Query<{ range?: HistoryRange }>, S["PortfolioHistory"]> }>;
    "/api/v1/account/runs": Item<{ get: Get<NoParams, S["Run"][]> }>;
    "/api/v1/account/violations": Item<{ get: Get<NoParams, S["Violations"]> }>;
    "/api/v1/account/reset": Item<{ post: Send<NoParams, S["ResetAccountRequest"], S["Account"]> }>;

    // ---- §18.2 orders
    "/api/v1/orders": Item<{
        get: Get<
            Query<{ status?: "OPEN" | "CLOSED" | "ALL"; symbol?: string; from?: string; to?: string; cursor?: string; limit?: number }>,
            S["PageOrder"]
        >;
        post: {
            parameters: NoParams;
            requestBody: Body<S["PlaceOrderRequest"]>;
            responses: { 200: Json<S["PlaceOrderResponse"]>; 201: Json<S["PlaceOrderResponse"]>; default: Problem };
        };
        delete: SendNoBody<Query<{ symbol?: string }>, S["CancelledOrders"]>;
    }>;
    "/api/v1/orders/preview": Item<{ post: Send<NoParams, S["PlaceOrderRequest"], S["Preview"]> }>;
    "/api/v1/orders/{orderId}": Item<{
        get: Get<PathP<{ orderId: string }>, S["Order"]>;
        delete: SendNoBody<PathP<{ orderId: string }>, S["Order"]>;
        patch: {
            parameters: PathP<{ orderId: string }>;
            requestBody: Body<S["ReplaceOrderRequest"]>;
            responses: { 201: Json<S["PlaceOrderResponse"]>; default: Problem };
        };
    }>;

    // ---- §18.2 watchlists
    "/api/v1/watchlists": Item<{
        get: Get<NoParams, S["Watchlist"][]>;
        post: Send<NoParams, S["CreateWatchlistRequest"], S["Watchlist"], 201>;
    }>;
    "/api/v1/watchlists/{id}": Item<{
        put: Send<PathP<{ id: string }>, S["UpdateWatchlistRequest"], S["Watchlist"]>;
        delete: DeleteNoContent<PathP<{ id: string }>>;
    }>;
    "/api/v1/watchlists/{id}/symbols": Item<{
        post: Send<PathP<{ id: string }>, S["AddWatchlistSymbolRequest"], S["Watchlist"]>;
    }>;
    "/api/v1/watchlists/{id}/symbols/{symbol}": Item<{
        delete: SendNoBody<PathP<{ id: string; symbol: string }>, S["Watchlist"]>;
    }>;

    // ---- §18.2 league
    "/api/v1/leaderboard": Item<{
        get: Get<Query<{ period?: LeaderboardPeriod; metric?: LeaderboardMetric; kind?: KindFilter; limit?: number }>, S["Leaderboard"]>;
    }>;
    "/api/v1/players": Item<{ get: Get<Query<{ kind?: PlayerKind }>, S["PlayerSummary"][]> }>;
    "/api/v1/players/{handle}": Item<{ get: Get<PathP<{ handle: string }>, S["PlayerProfile"]> }>;
    "/api/v1/players/{handle}/history": Item<{
        get: Get<PathP<{ handle: string }, { range?: HistoryRange }>, S["PublicHistory"]>;
    }>;
    "/api/v1/players/{handle}/trades": Item<{
        get: Get<PathP<{ handle: string }, { cursor?: string; limit?: number }>, S["PagePublicTrade"]>;
    }>;
    "/api/v1/feed": Item<{ get: Get<Query<{ cursor?: string; limit?: number }>, S["PageFeedItem"]> }>;

    // ---- §21.1 admin
    "/api/v1/admin/players": Item<{
        get: Get<Query<{ kind?: PlayerKind; status?: PlayerStatus }>, S["AdminPlayer"][]>;
        post: Send<NoParams, S["CreatePlayerRequest"], S["CreatePlayerResponse"], 201>;
    }>;
    "/api/v1/admin/players/{handle}": Item<{
        get: Get<PathP<{ handle: string }>, S["AdminPlayerDetail"]>;
        patch: Send<PathP<{ handle: string }>, S["UpdatePlayerRequest"], S["AdminPlayer"]>;
    }>;
    "/api/v1/admin/players/{handle}/live-view": Item<{
        post: Send<PathP<{ handle: string }>, S["LiveViewRequest"], S["AdminPlayerLiveView"]>;
    }>;
    "/api/v1/admin/players/{handle}/freeze": Item<{
        post: Send<PathP<{ handle: string }>, S["FreezePlayerRequest"], S["AdminPlayer"]>;
    }>;
    "/api/v1/admin/players/{handle}/unfreeze": Item<{ post: SendNoBody<PathP<{ handle: string }>, S["AdminPlayer"]> }>;
    "/api/v1/admin/players/{handle}/password": Item<{
        post: SendNoContent<PathP<{ handle: string }>, S["SetPasswordRequest"]>;
    }>;
    "/api/v1/admin/players/{handle}/reset": Item<{
        post: Send<PathP<{ handle: string }>, S["AdminResetRequest"], S["Account"]>;
    }>;
    "/api/v1/admin/players/{handle}/api-keys": Item<{
        get: Get<PathP<{ handle: string }>, S["ApiKeyInfo"][]>;
        post: Send<PathP<{ handle: string }>, S["CreateApiKeyRequest"], S["ApiKeyCreated"], 201>;
    }>;
    "/api/v1/admin/api-keys/{keyId}": Item<{ delete: DeleteNoContent<PathP<{ keyId: string }>> }>;
    "/api/v1/admin/cash-transfers/preview": Item<{
        post: Send<NoParams, S["CashTransferRequest"], S["CashTransferPreview"]>;
    }>;
    "/api/v1/admin/cash-transfers": Item<{
        get: Get<
            Query<{ handle?: string; batchId?: string; from?: string; to?: string; cursor?: string; limit?: number }>,
            S["PageTransferRow"]
        >;
        post: Send<NoParams, S["CashTransferRequest"], S["TransferBatchResult"], 201>;
    }>;
    "/api/v1/admin/settings": Item<{
        get: Get<NoParams, S["Settings"]>;
        put: Send<NoParams, S["UpdateSettingsRequest"], S["Settings"]>;
    }>;
    "/api/v1/admin/invites": Item<{
        get: Get<NoParams, S["Invite"][]>;
        post: Send<NoParams, S["CreateInviteRequest"], S["InviteCreated"], 201>;
    }>;
    "/api/v1/admin/invites/{inviteId}": Item<{ delete: DeleteNoContent<PathP<{ inviteId: string }>> }>;
    "/api/v1/admin/audit": Item<{
        get: Get<
            Query<{ category?: string; action?: string; actor?: string; from?: string; to?: string; cursor?: string; limit?: number }>,
            S["PageAuditRow"]
        >;
    }>;
    "/api/v1/admin/system": Item<{ get: Get<NoParams, S["SystemStatus"]> }>;
    "/api/v1/admin/system/jobs/{jobName}/run": Item<{
        post: {
            parameters: PathP<{ jobName: string }>;
            requestBody: Body<S["RunJobRequest"]>;
            responses: { 202: NoContent; default: Problem };
        };
    }>;
    "/api/v1/admin/engine/halt": Item<{ post: Send<NoParams, S["HaltEngineRequest"], S["SystemStatus"]> }>;
    "/api/v1/admin/engine/resume": Item<{ post: SendNoBody<NoParams, S["SystemStatus"]> }>;
    "/api/v1/admin/orders/{orderId}": Item<{ delete: SendNoBody<PathP<{ orderId: string }>, S["Order"]> }>;
}

export type webhooks = Record<string, never>;

export interface components {
    schemas: {
        // ---- common
        Warning: { code: string | null; message: string | null };
        Fees: { sec: string | null; taf: string | null; cat: string | null; total: string | null };

        // ---- identity (WS1)
        SignupRequest: { inviteCode: string | null; handle: string | null; displayName: string | null; password: string | null };
        ReauthRequest: { password: string | null };
        UpdateMeRequest: { displayName?: string | null; preferences?: S["UpdatePreferencesRequest"] | null };
        UpdatePreferencesRequest: {
            theme?: "DARK" | "LIGHT" | "SYSTEM" | null;
            upDown?: "GREEN_UP" | "RED_UP" | null;
            confirmOrders?: boolean | null;
            defaultOrderType?: "LIMIT" | "MARKET" | null;
            defaultTimeInForce?: TimeInForce | null;
            defaultQtyMode?: "SHARES" | "AMOUNT" | null;
        };
        ChangePasswordRequest: { currentPassword: string | null; newPassword: string | null };
        CreateApiKeyRequest: { name: string | null; scopes?: string[] | null; expiresInDays?: number | null };
        ApiKeyInfo: {
            keyId: string | null;
            name: string | null;
            scopes: string[] | null;
            createdAt: string | null;
            expiresAt: string | null;
            lastUsedAt: string | null;
            lastUsedIp: string | null;
            revokedAt: string | null;
        };
        ApiKeySnippets: { claudeCode: string | null; claudeApi: string | null; openaiResponses: string | null; curl: string | null };
        ApiKeyCreated: { key: S["ApiKeyInfo"] | null; token: string | null; snippets: S["ApiKeySnippets"] | null };
        PlayerSummary: {
            playerId: string | null;
            handle: string | null;
            displayName: string | null;
            kind: PlayerKind | null;
            aiModel: string | null;
            avatarSeed: string | null;
            memberSince: string | null;
            resets: number;
        };
        Notification: {
            id: string | null;
            kind: NotificationKind | null;
            title: string | null;
            body: string | null;
            payload: JsonObject | null;
            createdAt: string | null;
            readAt: string | null;
        };
        PageNotification: Page<S["Notification"]>;
        MarkNotificationsReadRequest: { ids?: string[] | null; all?: boolean | null };

        // ---- market (WS2)
        ClockHoliday: { date: string | null; name: string | null };
        ClockFeed: {
            profile: string | null;
            quality: DataQuality | null;
            status: "LIVE" | "DEGRADED" | "DOWN" | null;
            liveIexWindowEt: string | null;
        };
        Clock: {
            now: string | null;
            nowEt: string | null;
            session: SessionType | null;
            tradingDate: string | null;
            sessionEndsAt: string | null;
            nextSession: SessionType | null;
            nextSessionAt: string | null;
            nextRegularOpen: string | null;
            nextRegularClose: string | null;
            earlyClose: boolean;
            upcomingHolidays: S["ClockHoliday"][] | null;
            upcomingEarlyCloses: string[] | null;
            feed: S["ClockFeed"] | null;
        };
        AssetSummary: {
            symbol: string | null;
            name: string | null;
            exchange: string | null;
            type: AssetType | null;
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
            source: string | null;
        };
        Asset: {
            symbol: string | null;
            name: string | null;
            exchange: string | null;
            type: AssetType | null;
            tradable: boolean;
            fractionable: boolean;
            shortable: boolean;
            marginable: boolean;
            liquidityTier: LiquidityTier | null;
            status: "ACTIVE" | "INACTIVE" | null;
            week52High: string | null;
            week52Low: string | null;
            logoUrl: string | null;
            fundamentals: S["Fundamentals"] | null;
        };
        ExtendedLine: {
            session: SessionType | null;
            price: string | null;
            change: string | null;
            changePct: string | null;
            at: string | null;
        };
        Quote: {
            symbol: string | null;
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
            extended: S["ExtendedLine"] | null;
            asOf: string | null;
            ageMs: number | null;
            quality: DataQuality | null;
            source: string | null;
            halted: boolean;
            session: SessionType | null;
        };
        Quotes: { quotes: S["Quote"][] | null };
        TickExtended: { price: string | null; changePct: string | null };
        Tick: {
            symbol: string | null;
            last: string | null;
            change: string | null;
            changePct: string | null;
            extended: S["TickExtended"] | null;
            asOf: string | null;
            quality: DataQuality | null;
        };
        Bar: { t: string | null; o: string | null; h: string | null; l: string | null; c: string | null; v: number; vw: string | null };
        Bars: {
            symbol: string | null;
            timeframe: Timeframe | null;
            adjustment: AdjustmentMode | null;
            bars: S["Bar"][] | null;
            tailFrom: string | null;
            source: string | null;
        };
        MoverItem: {
            symbol: string | null;
            name: string | null;
            price: string | null;
            change: string | null;
            changePct: string | null;
            volume: number | null;
        };
        Movers: { list: MoverList | null; asOf: string | null; source: string | null; items: S["MoverItem"][] | null };
        UntrustedNews: { headline: string | null; summary: string | null };
        NewsItem: {
            id: string | null;
            publishedAt: string | null;
            source: string | null;
            url: string | null;
            symbols: string[] | null;
            imageUrl: string | null;
            untrusted: S["UntrustedNews"] | null;
        };
        NewsList: { items: S["NewsItem"][] | null };

        // ---- account and orders (WS3)
        AccountCash: {
            total: string | null;
            settled: string | null;
            unsettled: string | null;
            settledAvailable: string | null;
            withdrawable: string | null;
        };
        AccountGfv: { active: number; nextExpiry: string | null; settledCashOnlyAt: number; liquidationOnlyAt: number };
        Account: {
            accountId: string | null;
            runNo: number;
            type: AccountType | null;
            status: AccountStatus | null;
            restriction: AccountRestriction | null;
            restrictionUntil: string | null;
            restrictionReason: string | null;
            equity: string | null;
            longMarketValue: string | null;
            dividendsReceivable: string | null;
            cash: S["AccountCash"] | null;
            openBuyHolds: string | null;
            buyingPower: string | null;
            startingCash: string | null;
            netContributions: string | null;
            totalPnl: string | null;
            totalReturn: string | null;
            unitValue: string | null;
            dayPnl: string | null;
            dayReturn: string | null;
            gfv: S["AccountGfv"] | null;
            dayTradesLast5Days: number;
            openedAt: string | null;
            marksQuality: DataQuality | null;
            asOf: string | null;
        };
        Position: {
            symbol: string | null;
            name: string | null;
            qty: string | null;
            availableQty: string | null;
            avgCost: string | null;
            costBasis: string | null;
            last: string | null;
            mark: string | null;
            markQuality: DataQuality | null;
            marketValue: string | null;
            unrealizedPnl: string | null;
            unrealizedReturn: string | null;
            dayPnl: string | null;
            dayReturn: string | null;
            weight: string | null;
            fractionable: boolean;
            openedAt: string | null;
        };
        /** `ActivityDetailDto` (sealed, `@JsonTypeInfo(property = "type")`); account_event.payload, one shape per type. */
        ActivityDetail:
            | S["SplitDetail"]
            | S["NameChangeDetail"]
            | S["GfvDetail"]
            | S["ResetDetail"]
            | S["RestrictionDetail"]
            | S["AdminViewDetail"];
        SplitDetail: {
            type: "SPLIT";
            corporateActionId: string | null;
            oldRate: string | null;
            newRate: string | null;
            qtyBefore: string | null;
            qtyAfter: string | null;
            costBasis: string | null;
            cashInLieu: string | null;
            dustQty: string | null;
            realizedPnl: string | null;
        };
        NameChangeDetail: { type: "NAME_CHANGE"; corporateActionId: string | null; oldSymbol: string | null; newSymbol: string | null; qty: string | null };
        GfvDetail: {
            type: "GFV";
            violationId: string | null;
            orderId: string | null;
            unsettledAmount: string | null;
            fundingSettleDate: string | null;
            activeCount: number;
        };
        ResetDetail: {
            type: "RESET";
            side: "CLOSED" | "OPENED" | null;
            fromAccountId: string | null;
            toAccountId: string | null;
            runNo: number;
            finalEquity: string | null;
            startingCash: string | null;
            memo: string | null;
        };
        RestrictionDetail: {
            type: "RESTRICTION";
            from: AccountRestriction | null;
            to: AccountRestriction | null;
            until: string | null;
            reason: string | null;
        };
        AdminViewDetail: { type: "ADMIN_VIEW"; admin: string | null; reason: string | null };
        Activity: {
            id: string | null;
            at: string | null;
            type: ActivityType | null;
            symbol: string | null;
            side: Side | null;
            qty: string | null;
            price: string | null;
            fees: S["Fees"] | null;
            amount: string | null;
            balanceAfter: string | null;
            tradeDate: string | null;
            settleDate: string | null;
            memo: string | null;
            by: "SELF" | "ADMIN" | "SYSTEM" | null;
            orderId: string | null;
            executionId: string | null;
            transferId: string | null;
            detail: S["ActivityDetail"] | null;
        };
        PageActivity: Page<S["Activity"]>;
        RefQuote: {
            bid: string | null;
            ask: string | null;
            bidSize: number | null;
            askSize: number | null;
            last: string | null;
            quoteAt: string | null;
            confirmedAt: string | null;
            source: string | null;
            quality: DataQuality | null;
            clamped: boolean;
        };
        Execution: {
            executionId: string | null;
            orderId: string | null;
            symbol: string | null;
            side: Side | null;
            qty: string | null;
            price: string | null;
            principal: string | null;
            fees: S["Fees"] | null;
            netAmount: string | null;
            realizedPnl: string | null;
            liquidity: Liquidity | null;
            executedAt: string | null;
            tradeDate: string | null;
            settleDate: string | null;
            refQuote: S["RefQuote"] | null;
        };
        OrderEvent: {
            seq: number;
            type: OrderEventType | null;
            statusAfter: OrderStatus | null;
            at: string | null;
            detail: JsonObject | null;
        };
        Order: {
            orderId: string | null;
            clientOrderId: string | null;
            accountId: string | null;
            symbol: string | null;
            side: Side | null;
            type: OrderType | null;
            qty: string | null;
            notional: string | null;
            fractional: boolean;
            limitPrice: string | null;
            stopPrice: string | null;
            trailAmount: string | null;
            trailPercent: string | null;
            watermarkPrice: string | null;
            collarPrice: string | null;
            timeInForce: TimeInForce | null;
            extendedHours: boolean;
            status: OrderStatus | null;
            waitReason: WaitReason | null;
            statusReason: string | null;
            triggered: boolean;
            filledQty: string | null;
            filledPrincipal: string | null;
            avgFillPrice: string | null;
            fees: string | null;
            reservedCash: string | null;
            reservedQty: string | null;
            sessionDate: string | null;
            receivedAt: string | null;
            acceptedAt: string | null;
            eligibleAt: string | null;
            activatedAt: string | null;
            triggeredAt: string | null;
            expiresAt: string | null;
            doneAt: string | null;
            replacesOrderId: string | null;
            replacedByOrderId: string | null;
            channel: Channel | null;
            rationale: string | null;
            executions: S["Execution"][] | null;
            events: S["OrderEvent"][] | null;
        };
        PageOrder: Page<S["Order"]>;
        CancelledOrders: { cancelled: S["Order"][] | null };
        Estimate: {
            price: string | null;
            qty: string | null;
            principal: string | null;
            fees: S["Fees"] | null;
            hold: string | null;
            quoteAsOf: string | null;
            quoteQuality: DataQuality | null;
        };
        PlaceOrderRequest: {
            clientOrderId: string | null;
            symbol: string | null;
            side: Side | null;
            type: OrderType | null;
            qty?: string | null;
            notional?: string | null;
            limitPrice?: string | null;
            stopPrice?: string | null;
            trailAmount?: string | null;
            trailPercent?: string | null;
            timeInForce?: TimeInForce | null;
            extendedHours?: boolean | null;
            rationale?: string | null;
            shareNote?: boolean | null;
            acknowledge?: string[] | null;
        };
        ReplaceOrderRequest: {
            clientOrderId: string | null;
            qty?: string | null;
            limitPrice?: string | null;
            stopPrice?: string | null;
            trailAmount?: string | null;
            trailPercent?: string | null;
            timeInForce?: TimeInForce | null;
            extendedHours?: boolean | null;
            rationale?: string | null;
        };
        ClosePositionRequest: { clientOrderId: string | null; rationale?: string | null };
        ResetAccountRequest: { confirm: string | null };
        PlaceOrderResponse: {
            order: S["Order"] | null;
            estimate: S["Estimate"] | null;
            buyingPowerBefore: string | null;
            buyingPowerAfter: string | null;
            warnings: S["Warning"][] | null;
            replayed: boolean;
        };
        PreviewError: { code: string | null; field: string | null; message: string | null; hint: string | null };
        Preview: {
            valid: boolean;
            errors: S["PreviewError"][] | null;
            warnings: S["Warning"][] | null;
            estimate: S["Estimate"] | null;
            buyingPowerBefore: string | null;
            buyingPowerAfter: string | null;
            sessionNote: string | null;
        };
        Run: {
            accountId: string | null;
            runNo: number;
            status: AccountStatus | null;
            openedAt: string | null;
            closedAt: string | null;
            startingCash: string | null;
            finalEquity: string | null;
            netContributions: string | null;
            return: string | null;
            pnl: string | null;
        };
        Violation: {
            id: string | null;
            type: ViolationType | null;
            symbol: string | null;
            orderId: string | null;
            unsettledAmount: string | null;
            fundingSettleDate: string | null;
            tradeDate: string | null;
            occurredAt: string | null;
            expiresAt: string | null;
        };
        Violations: {
            restriction: AccountRestriction | null;
            restrictionUntil: string | null;
            active: S["Violation"][] | null;
            expired: S["Violation"][] | null;
        };

        // ---- portfolio, league and watchlists (WS4)
        HistoryPoint: {
            t: string | null;
            equity: string | null;
            netContributions: string | null;
            pnl: string | null;
            unitValue: string | null;
            benchmarkIndex: string | null;
        };
        HistoryFlow: { t: string | null; type: TransferType | null; amount: string | null; memo: string | null };
        HistorySummary: { return: string | null; pnl: string | null; excessVsBenchmark: string | null; maxDrawdown: string | null };
        PortfolioHistory: {
            range: HistoryRange | null;
            points: S["HistoryPoint"][] | null;
            flows: S["HistoryFlow"][] | null;
            summary: S["HistorySummary"] | null;
        };
        Watchlist: { id: string | null; name: string | null; sortOrder: number; symbols: string[] | null };
        CreateWatchlistRequest: { name: string | null; symbols?: string[] | null };
        UpdateWatchlistRequest: { name: string | null; symbols: string[] | null };
        AddWatchlistSymbolRequest: { symbol: string | null };
        BenchmarkReturn: { symbol: string | null; return: string | null };
        Arena: { humansMedianReturn: string | null; aiMedianReturn: string | null };
        LeaderboardRow: {
            rank: number | null;
            rankChange: number | null;
            eligible: boolean;
            eligibilityNote: string | null;
            partialPeriod: boolean;
            rebased: boolean;
            player: S["PlayerSummary"] | null;
            apiTrader: boolean;
            return: string | null;
            pnl: string | null;
            excessVsBenchmark: string | null;
            maxDrawdown: string | null;
            equity: string | null;
            netContributions: string | null;
            flowsInPeriod: string | null;
            fills: number;
            symbolsTraded: number;
            winRate: string | null;
            topHolding: string | null;
            spark: string[] | null;
        };
        Leaderboard: {
            period: LeaderboardPeriod | null;
            metric: LeaderboardMetric | null;
            kind: KindFilter | null;
            periodStart: string | null;
            asOf: string | null;
            benchmark: S["BenchmarkReturn"] | null;
            arena: S["Arena"] | null;
            rows: S["LeaderboardRow"][] | null;
            me: S["LeaderboardRow"] | null;
        };
        ProfileStat: { return: string | null; pnl: string | null; rank: number | null };
        ProfileStats: {
            TODAY: S["ProfileStat"] | null;
            WEEK: S["ProfileStat"] | null;
            MONTH: S["ProfileStat"] | null;
            YTD: S["ProfileStat"] | null;
            ALL: S["ProfileStat"] | null;
        };
        DepositsSummary: { count: number; total: string | null };
        ProfileHolding: { symbol: string | null; weight: string | null; qty: string | null };
        UntrustedRationale: { rationale: string | null };
        PublicTrade: {
            executedAt: string | null;
            symbol: string | null;
            side: Side | null;
            qty: string | null;
            price: string | null;
            realizedPnl: string | null;
            viaApi: boolean;
            untrusted: S["UntrustedRationale"] | null;
        };
        PagePublicTrade: Page<S["PublicTrade"]>;
        PlayerProfile: {
            player: S["PlayerSummary"] | null;
            ownerHandle: string | null;
            runNo: number;
            stats: S["ProfileStats"] | null;
            equity: string | null;
            netContributions: string | null;
            deposits: S["DepositsSummary"] | null;
            fills: number;
            winRate: string | null;
            moneyWeightedReturnAll: string | null;
            holdingsAsOf: string | null;
            holdings: S["ProfileHolding"][] | null;
            recentTrades: S["PublicTrade"][] | null;
        };
        PublicHistoryPoint: { t: string | null; unitValue: string | null; equity: string | null };
        PublicHistory: { range: HistoryRange | null; points: S["PublicHistoryPoint"][] | null };
        UntrustedFeedText: { rationale: string | null; memo: string | null };
        FeedPayload: {
            side: Side | null;
            qty: string | null;
            price: string | null;
            realizedPnl: string | null;
            amount: string | null;
            period: string | null;
            untrusted: S["UntrustedFeedText"] | null;
        };
        FeedItem: {
            id: string | null;
            at: string | null;
            kind: "FILL" | "POSITION_CLOSED" | "NEW_LEADER" | "RESET" | "DEPOSIT" | "JOINED" | null;
            player: S["PlayerSummary"] | null;
            symbol: string | null;
            payload: S["FeedPayload"] | null;
        };
        PageFeedItem: Page<S["FeedItem"]>;

        // ---- admin (§21.1, WS1 controllers over WS3/WS4 contracts)
        AdminPlayer: {
            playerId: string | null;
            handle: string | null;
            displayName: string | null;
            kind: PlayerKind | null;
            aiModel: string | null;
            avatarSeed: string | null;
            memberSince: string | null;
            resets: number;
            role: PlayerRole | null;
            status: PlayerStatus | null;
            ownerHandle: string | null;
            accountId: string | null;
            runNo: number;
            equity: string | null;
            cash: string | null;
            netContributions: string | null;
            pnl: string | null;
            return: string | null;
            openOrders: number;
            activeKeys: number;
            restriction: AccountRestriction | null;
            lastSeenAt: string | null;
            createdAt: string | null;
        };
        CreatePlayerRequest: {
            kind: PlayerKind | null;
            handle: string | null;
            displayName: string | null;
            password?: string | null;
            aiModel?: string | null;
            ownerHandle?: string | null;
            startingCash?: string | null;
            issueKey?: boolean | null;
        };
        CreatePlayerResponse: { player: S["AdminPlayer"] | null; apiKey: S["ApiKeyCreated"] | null };
        EodHolding: { symbol: string | null; qty: string | null; mark: string | null; marketValue: string | null };
        AdminPlayerDetail: {
            player: S["AdminPlayer"] | null;
            apiTradingAllowed: boolean;
            runs: S["Run"][] | null;
            balances: S["Account"] | null;
            holdingsAsOf: string | null;
            eodHoldings: S["EodHolding"][] | null;
            transfers: S["TransferRow"][] | null;
            apiKeys: S["ApiKeyInfo"][] | null;
            recentAudit: S["AuditRow"][] | null;
        };
        AdminPlayerLiveView: {
            player: S["PlayerSummary"] | null;
            viewedAt: string | null;
            reason: string | null;
            positions: S["Position"][] | null;
            openOrders: S["Order"][] | null;
        };
        LiveViewRequest: { reason: string | null };
        UpdatePlayerRequest: {
            displayName?: string | null;
            aiModel?: string | null;
            status?: PlayerStatus | null;
            role?: PlayerRole | null;
            apiTradingAllowed?: boolean | null;
        };
        FreezePlayerRequest: { reason: string | null; revokeKeys?: boolean | null };
        SetPasswordRequest: { newPassword: string | null };
        AdminResetRequest: { startingCash?: string | null; memo: string | null };
        Settings: {
            "league.defaultStartingCash": string | null;
            "league.allowSelfReset": boolean;
            "league.seasonStart": string | null;
            "league.eligibility.minFills": number;
            "league.eligibility.minSymbols": number;
            "social.tradeFeedDelay": string | null;
            "social.holdingsVisibility": HoldingsVisibility | null;
            "social.showDepositsInFeed": boolean;
            "bots.requireRationale": boolean;
            "league.humanKindExcludesApiTraders": boolean;
        };
        UpdateSettingsRequest: {
            "league.defaultStartingCash"?: string | null;
            "league.allowSelfReset"?: boolean | null;
            "league.seasonStart"?: string | null;
            "league.eligibility.minFills"?: number | null;
            "league.eligibility.minSymbols"?: number | null;
            "social.tradeFeedDelay"?: string | null;
            "social.holdingsVisibility"?: HoldingsVisibility | null;
            "social.showDepositsInFeed"?: boolean | null;
            "bots.requireRationale"?: boolean | null;
            "league.humanKindExcludesApiTraders"?: boolean | null;
        };
        AuditRow: {
            id: number;
            occurredAt: string | null;
            category: string | null;
            action: string | null;
            outcome: string | null;
            actor: string | null;
            actorKind: PlayerKind | null;
            channel: Channel | null;
            apiKeyId: string | null;
            mcpClient: string | null;
            mcpTool: string | null;
            ip: string | null;
            requestId: string | null;
            targetType: string | null;
            targetId: string | null;
            detail: JsonObject | null;
        };
        PageAuditRow: Page<S["AuditRow"]>;
        SystemEngine: { status: EngineState | null; queueDepth: number; lastCommandAt: string | null; haltReason: string | null };
        SystemInvariants: { lastRunAt: string | null; ok: boolean; failures: string[] | null };
        SystemProvider: {
            id: string | null;
            state: string | null;
            streamSymbolsUsed: number;
            streamSymbolsMax: number;
            restPerMinUsed: number;
            restPerMinMax: number;
            lastMessageAt: string | null;
            capabilities: JsonObject | null;
            probe: JsonObject | null;
        };
        SystemMarketData: {
            profile: string | null;
            staleSymbols: string[] | null;
            corporateActionHolds: string[] | null;
            providers: S["SystemProvider"][] | null;
        };
        SystemJob: { name: string | null; lastRunKey: string | null; status: string | null; finishedAt: string | null; detail: string | null };
        SystemStatus: {
            version: string | null;
            startedAt: string | null;
            db: string | null;
            engine: S["SystemEngine"] | null;
            invariants: S["SystemInvariants"] | null;
            marketData: S["SystemMarketData"] | null;
            jobs: S["SystemJob"][] | null;
            corporateActionsNeedingAdmin: number;
        };
        HaltEngineRequest: { reason: string | null };
        RunJobRequest: { runKey?: string | null };
        CreateInviteRequest: { maxUses: number | null; expiresInHours: number | null };
        InviteCreated: { inviteId: string | null; code: string | null; url: string | null; expiresAt: string | null };
        Invite: { inviteId: string | null; uses: number; maxUses: number; expiresAt: string | null; revokedAt: string | null };
        /** `TransferTargetRequest` (sealed, `@JsonTypeInfo(property = "scope")`). */
        TransferTargetRequest: S["SingleTargetRequest"] | S["AllTargetRequest"];
        SingleTargetRequest: { scope: "SINGLE"; handle: string | null };
        AllTargetRequest: { scope: "ALL"; kinds?: PlayerKind[] | null };
        CashTransferRequest: {
            target: S["TransferTargetRequest"] | null;
            type: TransferType | null;
            amount: string | null;
            treatAs?: AdjustmentTreatment | null;
            memo: string | null;
            allOrNothing?: boolean | null;
            acceptDegradedMarks?: boolean | null;
        };
        TransferPreviewAccount: {
            handle: string | null;
            accountId: string | null;
            cash: string | null;
            withdrawable: string | null;
            equityBefore: string | null;
            unitValue: string | null;
            marksQuality: DataQuality | null;
            worstMarkBasis: string | null;
            wouldApply: boolean;
            skipReason: string | null;
            warnings: S["Warning"][] | null;
        };
        CashTransferPreview: { asOf: string | null; session: SessionType | null; accounts: S["TransferPreviewAccount"][] | null };
        TransferResult: {
            transferId: string | null;
            handle: string | null;
            displayName: string | null;
            accountId: string | null;
            status: TransferStatus | null;
            skipReason: string | null;
            amount: string | null;
            equityBefore: string | null;
            unitValue: string | null;
            cashAfter: string | null;
            marksQuality: DataQuality | null;
            rebased: boolean;
            warnings: S["Warning"][] | null;
        };
        TransferBatchResult: {
            batchId: string | null;
            scope: TransferScope | null;
            type: TransferType | null;
            amount: string | null;
            memo: string | null;
            createdAt: string | null;
            applied: number;
            skipped: number;
            results: S["TransferResult"][] | null;
        };
        TransferRow: {
            transferId: string | null;
            batchId: string | null;
            handle: string | null;
            accountId: string | null;
            type: TransferType | null;
            amount: string | null;
            isContribution: boolean;
            status: TransferStatus | null;
            skipReason: string | null;
            memo: string | null;
            effectiveAt: string | null;
            by: string | null;
            equityBefore: string | null;
            unitValue: string | null;
        };
        PageTransferRow: Page<S["TransferRow"]>;
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}

export type $defs = Record<string, never>;
