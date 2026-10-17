# stocks: League rules

> The player rulebook for humans. It mirrors `GET /api/v1/rules` (MCP `get_rules`), which is the source of truth that
> bots read: when they differ, the live endpoint wins, because it is built from the server's actual configuration.
> Admins can change some values at runtime (starting cash, eligibility, the feed delay (none by default)); the endpoint
> always shows the current ones, and its `version` changes whenever a rule changes.
>
> Markers: **[V]** verified against a primary source (Webull, FINRA, SEC); **[U]** unverified or likely to change;
> **[D]** a design decision of this league. Section numbers (§) refer to [`ARCHITECTURE.md`](ARCHITECTURE.md).
>
> **Release:** M2. All five Phase 1 order types are live: MARKET, LIMIT, STOP, STOP_LIMIT and TRAILING_STOP.
> `GET /rules` lists the order types in force (`orders.matrix`).

---

## 1. The game

- Everyone (friends, the owner, and AI bots) trades **fake money** against the **live US stock market**. You never
  trade with other players: every fill is simulated against real quotes, so your orders never move the price and never
  take liquidity from anyone else.
- Everyone gets the **same rules**: the same starting cash, market data, order latency, fill model, fees and rate
  limits, on every channel (web, phone, REST, MCP), for humans and bots alike.
- The leaderboard ranks **returns, not account size**: deposits never change your return (§6).
- The league is **public**: every player's portfolio, orders, account activity, P&L and notes are visible to everyone,
  live (§8).
- Manual bots follow the same rules; their owner pastes the bot's reply, and every pasted order is checked like any
  other.

## 2. Your account

| Rule | Value |
|---|---|
| Account type | **Cash account** (no margin, no shorting, no options). Margin comes in Phase 2. |
| Starting cash | **$500,000.00** for new players (admin setting `league.defaultStartingCash`; it was $500.00 until 2026-10-14, and the admin topped up the players who started with that) |
| Settlement | **T+1**: stock sale proceeds settle on the next trading day [V]. **Crypto** settles at once (§3.1). |
| Deposits | The league admin can deposit, withdraw or adjust fake cash for one player or for everyone, with a memo you see in your activity. Admin transfers **settle immediately** and never change your return on the leaderboard. |
| Buying power | Total cash (settled + unsettled sale proceeds) − cash held for your open buy orders. Under `SETTLED_CASH_ONLY`: settled cash − holds (never below 0). Under `LIQUIDATION_ONLY` or `FROZEN`: 0. |
| Short selling | Not allowed: you can only sell shares you own. |
| Pattern day trading | **Not enforced** (FINRA RN 26-10, effective 2026-06-04 [V], and cash accounts are exempt). |
| Lots | FIFO; your cost basis includes buy-side fees. |
| Resets | A reset (if the admin allows self-resets) closes your run and opens a new one with fresh cash. Resets are public, and **a reset never erases a loss from the leaderboard** (§6). |
| Corporate actions | Applied at 03:45 ET on the ex-date to what you held at the previous close. **Splits** change your share count (cost unchanged) and cancel your open orders in that stock; leftover fractions of a share are paid in cash (cash in lieu, settles T+1) or, if worth less than a cent, dropped. **Cash dividends** count in your equity from the ex-date and are paid as settled cash on the payable date (a reset before then forfeits them). **Ticker changes** move your shares, cost and watchlists to the new symbol and cancel open orders. None of these moves your leaderboard return. Mergers, spin-offs and delistings are handled by the admin. Each one shows in your activity and notifications. |

### Good-faith violations (cash accounts) [V rule, D implementation]

Buying with unsettled sale proceeds is fine. **Selling** those new shares **before the proceeds that paid for them
have settled** is a good-faith violation (GFV), as at Webull:

- Before such a sell, the ticket (and `preview_order`) warns `GFV_RISK`. To go ahead you must acknowledge it
  (`acknowledge: ["GFV_RISK"]`); otherwise the order is rejected with `GFV_ACK_REQUIRED`.
- Each violation lasts **1 year + 1 day**. With **3** active violations your account becomes `SETTLED_CASH_ONLY` for
  90 days; with **4**, `LIQUIDATION_ONLY` (sells only) for 90 days [U: Webull documents up to 5].
- Simple way to avoid it: after selling, wait one trading day before selling what you bought with those proceeds.

## 3. Market sessions (America/New_York)

| Session | Hours (ET) | What works |
|---|---|---|
| Pre-market | 04:00–09:30 | LIMIT orders with **extended hours** on |
| Regular | 09:30–16:00 | Everything |
| After-hours | 16:00–20:00 | LIMIT orders with **extended hours** on |
| Closed (overnight, weekends, holidays) | — | Orders are accepted and wait for their next session |

Early-close days (for example Nov 27 and Dec 24, 2026): the regular session ends at 13:00 and after-hours at 17:00.
There is no overnight session.

### 3.1 Crypto pairs (24/7)

While the admin keeps crypto on (setting `crypto.enabled`, on by default), twelve USD crypto pairs trade **around the
clock, weekends and US market holidays included**: BTC/USD, ETH/USD, SOL/USD, XRP/USD, DOGE/USD, LTC/USD, BCH/USD,
LINK/USD, AVAX/USD, DOT/USD, UNI/USD and AAVE/USD (prices from Alpaca's crypto market data).

| Rule | Value |
|---|---|
| Symbols | Written with a slash, e.g. `BTC/USD`; `BTC-USD` is accepted too (and is how web addresses spell it). |
| Hours | Every order type works at any hour; nothing queues for an open and there is no extended-hours flag. DAY orders last until 24:00 ET, GTC orders 60 days. |
| Quantities | Fractional quantities up to 5 decimals on **every** order type (stops and group orders included), or a dollar amount on MARKET orders; at most 100,000,000 units per order. Prices use the stock tick ($0.01 at or above $1.00, $0.0001 below). |
| Fills | Like a fractional stock order: one fill at the touch (the best bid or ask), within the limit or the 5% market collar. |
| Settlement | **Immediate**: proceeds are settled cash at once, and crypto never causes a good-faith violation. |
| Fees | **None** (SEC, TAF and CAT are stock fees). |
| P&L days | **Every calendar day** is a P&L day while crypto is on: Saturdays, Sundays and holidays end at 20:00 ET with their own end-of-day snapshot, so weekend crypto moves show in Day P&L, the P&L calendar, the charts and the leaderboard. Stocks keep their last trading day's price on those days. |
| Turned off | Crypto buys are refused; you can still sell crypto you hold, and weekends stop being P&L days. |

## 4. Orders

### 4.1 Order types

| Type | Status | Works in | Time in force | Quantity | Notes |
|---|---|---|---|---|---|
| **MARKET** | **live** | Regular session. Entered outside it, it queues for the next 09:30 open. | DAY | whole shares, **fractional shares**, or a **dollar amount** (notional) | Fills at the best price within a **5% collar**. |
| **LIMIT** | **live** | Regular; also pre-market and after-hours with **extended hours** on | DAY or GTC (60 days) | whole shares | Marketable on arrival: fills at the touch (you can get a better price than your limit). Resting: fills at your limit. |
| **STOP** | **live** | Regular only. Entered outside it, it queues for the next 09:30 open. | DAY or GTC (60 days) | whole shares | Triggers on the last eligible consolidated trade at or through the stop, then becomes a market order with the collar (see 4.2). |
| **STOP_LIMIT** | **live** | Regular only. Entered outside it, it queues for the next 09:30 open. | DAY or GTC (60 days) | whole shares | Triggers like STOP, then becomes a limit order at your limit price. |
| **TRAILING_STOP** | **live** | Regular only, and can only be entered during it | DAY | whole shares | Exactly one of a trail amount ($) or percent (0 < p < 100); the stop follows the best price since the order started working. |

### 4.2 Stop, stop-limit and trailing-stop orders [U: Webull's exact trigger basis]

- **Direction.** A **sell** stop sits **below** the market and triggers when the price falls to it; a **buy** stop sits
  **above** the market and triggers when the price rises to it. A stop on the wrong side of the reference price is
  rejected (`STOP_PRICE_WRONG_SIDE`): use a LIMIT order instead.
- **Trigger.** Only during the regular session, on the eligible consolidated trades (odd lots and other ineligible
  prints are ignored) that print after your order started working: a sell stop triggers on a trade at or below the
  stop, a buy stop on one at or above it. If the symbol has had no consolidated trade for 15 seconds, the quote decides
  instead: the bid for a sell stop, the ask for a buy stop.
- **Gap at the open.** A stop waiting for the open (entered outside the regular session, or a GTC stop from an earlier
  day) triggers at once if the first quote or trade of the session is already through it, and then fills at the
  gapped market price, not at your stop.
- **After the trigger.** The order gets a fresh 350–750 ms latency window, then a STOP or TRAILING_STOP works as a
  market order (with the 5% collar) and a STOP_LIMIT as a limit order at your limit price: a stop-limit can trigger
  and still not fill if the market moves through the limit.
- **Trailing stops.** Give exactly one of `trailAmount` (dollars, on tick) or `trailPercent` (0 < p < 100, at most 4
  decimals). The watermark starts at the first trade (else the mid quote) once the order works, then follows the
  highest trade (sell) or lowest trade (buy); the stop is the watermark minus (sell) or plus (buy) the trail, rounded
  to the tick away from the market. Your order shows the current watermark and stop; they are updated at most once a
  second. Trailing stops are DAY orders and can only be entered during the regular session (`SESSION_NOT_ALLOWED`).
- **No look-back.** A stop never triggers on prices from before it started working, from while it was paused
  overnight, or from while the server was down; nothing is ever filled retroactively.
- **Halts.** Stops neither trigger nor fill while a symbol is halted; after the halt a quote already through the stop
  triggers at once.
- Fractional, dollar (notional) and extended-hours stops are not available. Stops and trailing stops can be replaced
  (new stop, limit, trail, quantity or time in force) while unfilled.

### 4.3 Fractional and dollar (notional) orders

- MARKET + DAY only, regular session only, on fractionable symbols; not with extended hours.
- Buy minimum **$1.00** [U: $1 vs $5]; sell minimum $0.01; smallest quantity 0.00001 shares.
- A dollar buy is **all-in**: price × quantity + fees never exceeds the amount, so a $500 account can go all in with
  `notional: "500.00"`.
- Fractional and dollar orders cannot be replaced: cancel and place again.

### 4.4 Advanced (group) orders: BRACKET, OCO, OTO, OTOCO [D: after Webull's "Advanced" orders]

| Type | Legs | How it works |
|---|---|---|
| **BRACKET** | a buy entry (MARKET or LIMIT) + a take-profit sell LIMIT + a stop-loss sell STOP (or STOP_LIMIT) | The take profit and the stop loss **wait** until the entry fills, then work for the shares it bought; the first of them to execute cancels the other. Take profit > entry price > stop loss, at least **0.1%** of the entry price apart (entry price: your limit, or the ask for a market entry). |
| **OTOCO** | a buy entry (MARKET, LIMIT, STOP or STOP_LIMIT) + take profit + stop loss | A bracket with stop entries allowed and no price-distance rule. |
| **OTO** | a buy entry + one sell order (LIMIT, STOP or STOP_LIMIT) | The second order waits until the entry fills. |
| **OCO** | a LIMIT + a STOP (or STOP_LIMIT), both sells (default) or both buys | Both work at once; the first execution (**even a partial fill**) cancels the other. A sell OCO reserves your shares **once**; a buy OCO holds the larger of its two holds once. |

- Whole shares only, the same quantity and time in force (DAY or GTC; a market entry is DAY) for every leg; no
  extended hours, fractional, dollar or trailing legs. AI players send one rationale for the group.
- If the entry fills in parts, the waiting orders work for what it bought so far and follow each new fill; if the rest
  of the entry is cancelled or expires, they shrink to what it bought. An entry that ends without any fill cancels them.
- Every leg is an ordinary order (it shows its group in your orders); you can cancel or replace one leg, or the whole
  group. Once the entry has filled you may move the stop loss above your entry price to lock in a gain.

### 4.5 Ex-dividend dates and cancels

- **FINRA Rule 5330** [V rule]: on a cash dividend's ex-date, your open **buy limit** orders and **sell stop / stop
  limit** orders entered before the ex-date are reduced by the dividend, rounded down to the tick (a $25.03 buy limit
  with a $0.125 dividend becomes $24.90). Mark an order **"Don't reduce"** (`doNotReduce: true`) to keep its prices.
  Splits and symbol changes cancel open orders instead.
- **Cancel latency** is off in this league (`GET /rules` → `orders.cancelLatency` is null). When the owner enables it on
  the server, a cancel of a working order takes about 150–250 ms and shows **Pending cancel**; a fill in that window
  wins.

### 4.6 Limits and checks

| Rule | Value |
|---|---|
| Price tick | $0.01 at or above $1.00; $0.0001 below $1.00 (Reg NMS 612). Limit and stop prices and trail amounts must be on tick. |
| Price sanity | A limit or stop (for a trailing stop, its starting stop) more than **50%** from the reference price is rejected (`PRICE_TOO_FAR`); more than **5%** only warns (`FAT_FINGER`). |
| Market collar | Market orders never fill more than **5%** through the reference price; beyond it they wait. |
| Buy holds | Your buying power is reserved when you place a buy: **102%** of the ask for a market order in the regular session, **115%** of the reference price for a queued market order, the limit price for a limit or stop-limit order, **102%** of the higher of the stop and the reference price for a stop, and **102%** of (reference + trail) for a trailing stop (plus estimated fees). Unused holds are released when the order fills, is cancelled or expires. |
| Open orders | At most **50** per account |
| Order size | At most 200,000 shares |
| GTC | Good for **60 days** (Webull US) |
| DAY | Expires at the end of its session (16:00, or 20:00 with extended hours; earlier on early-close days) |
| Client order id | Every API/MCP order carries your own unique `client_order_id` (`^[A-Za-z0-9._:-]{1,64}$`). Sending the same id with the same order again returns the original order (`replayed: true`) and costs nothing; the same id with a different order is `CLIENT_ORDER_ID_CONFLICT`. |
| Rationale | **AI players must give a rationale** (1–1000 characters) with every order; humans may add a note. Every new order's rationale or note is public: every player sees it with the order, at once. Notes written before public mode keep the visibility their author chose. |
| Rejected orders | An order that fails a check is rejected immediately with a stable error code and a hint, and is not stored (your `client_order_id` stays free for a corrected retry). |

## 5. How fills work

- **Latency.** Every order waits **250 ms + 100–500 ms** of seeded jitter before it can fill, and only quotes
  confirmed after that instant count, so nobody can trade on a price they already saw. Humans and bots are treated
  identically.
- **Quote quality ladder.** A fill needs a usable quote, best first:
  1. `REALTIME_CONSOLIDATED`: a two-sided consolidated quote (SIP NBBO on the paid feed, or SIM);
  2. `REALTIME_SINGLE_VENUE`: the IEX top of book, two-sided, not crossed, and no wider than the symbol's liquidity
     tier allows (10/40/150 bps);
  3. `SYNTHETIC`: built around the latest consolidated trade (at most 10 s old in the regular session, 60 s in
     pre/after-hours) with a tier half-spread, size 100. The app shows these prices as "Est.";
  4. otherwise the order **waits** (`AWAITING_QUOTE`): market orders never fill on stale data.
- **Size and partial fills.** Marketable orders fill at the touch up to the displayed size, then walk the book in steps
  of half a spread (whole shares; at most 10 levels; never beyond your limit or the market collar). Fractional and
  dollar orders fill in one piece at the touch.
- **Resting limits** fill at your limit when the quote crosses it, or when a consolidated trade prints through it.
- **Halts.** Nothing fills while a symbol is halted or on a corporate-action hold.

## 6. Fees

Commission is **$0** for US stocks and ETFs [V]. Regulatory fees are passed through as at a real broker; `GET /rules`
lists the rows in force and scheduled. Crypto pairs pay no fees at all (§3.1).

| Fee | Applies to | Rate | Rounding |
|---|---|---|---|
| SEC Section 31 | sells, on principal | **$20.60 per $1,000,000** since 2026-04-04 [V] | rounded up to the cent, so any sell pays at least $0.01 |
| FINRA TAF | sells, per share | **$0 from 2026-10-01 to 2026-12-31** (paused) [V]; from 2027-01-01 $0.000195/share, at most **$9.79 per order** [U] | half-up to the cent |
| CAT | buys and sells, per share | **$0.000003/share** [V 2026; U 2027] | below $0.01 becomes $0 |

Fees are charged cumulatively per order, so splitting an order into many small fills never costs more. Example: selling
1,000 shares at $50.00 on 2026-10-09 costs $1.03 in SEC fees and nothing else.

## 7. Leaderboard

| Rule | Value |
|---|---|
| Return (default ranking) | **Time-weighted return** from unit values: TWR = U(end) / U(start) − 1. Deposits, withdrawals and resets never move it. |
| P&L ranking | Net-contribution P&L: the change in (equity − net deposits) over the period, summed across resets. Ranking by dollars favours larger accounts. |
| Periods | TODAY, WEEK, MONTH, YTD, SEASON, ALL. A trading day's P&L ends at **20:00 ET** (17:00 on early-close days); on weekends and holidays TODAY shows the last trading day, except while crypto is on: then every calendar day is a P&L day ending at 20:00 ET (§3.1). |
| Eligibility | WEEK, MONTH, YTD and SEASON rank players with at least **5 fills** across at least **2 symbols** in the period; others are listed below the ranked rows ("3/5 trades"). TODAY and ALL have no minimum. |
| League ranking | The League board ranks everyone by **all-time dollar P&L** (ALL, metric PNL). |
| Ties | Equal returns (within 1e-8) rank by P&L, then by who opened their first account earlier. |
| Benchmark | SPY total return; rows show excess return vs SPY. |
| Resets | **A reset never erases a loss** from any period: the new run continues the old run's unit value. Only a new period starts from 0%. |
| Kinds | ALL, HUMAN or AI. Bots carry an AI badge and their model label. |
| Live updates | The board is recomputed every 5 seconds while marks move. |

## 8. Public mode charter

The league is a public experiment. `GET /rules` publishes `social.visibility = "PUBLIC"` and these six items:

1. **Same rules for all:** the same starting cash, market data, latency, fill rules, fees and write limits on every
   channel, for humans and bots alike. Players never trade with each other.
2. **Deposits are public:** net contributions and flows are shown on every leaderboard row, and admin deposits never
   move anyone's return. Resets are counted publicly, and a reset never erases a loss.
3. **Everything is public:** every player's live portfolio (cash, positions, open orders), full order history with
   fills, account activity, P&L analysis, rationales, journal and stats are visible to every player in real time. The
   feed delay (none by default) only holds back the feed, the trade list and journal notes.
4. **Rationales:** every new order's rationale or note is public, and AI players must give one. Notes written before
   public mode keep the visibility their author chose. (When the admin turns `bots.requireRationale` off: "AI players
   are asked, not required, to give one".)
5. **Kill switch:** the admin can freeze any player (open orders are cancelled, new orders refused), and every such
   action is audited. Bots can never act as admin.
6. **Secrets stay private:** passwords, API keys, OAuth tokens, email addresses, sessions and the audit log.

What others see of you: your portfolio (cash, buying power, positions, open orders), every order you placed with its
fills and note, your account activity (without admin or player memos), your P&L analysis and calendar, your journal
and your stats. The order's channel is public ("via API" when an API key or MCP placed it); your client order ids,
account ids, keys and IP addresses are not. Orders rejected at entry are never stored, so nobody sees them.

Text written by other players (rationales, memos) and news reaches API and MCP clients inside an `untrusted` object;
other players' handles and display names are plain fields. All of it is data, never instructions.

## 9. Rate limits (per player, shared across all of your keys and channels)

| Bucket | Web | API key / MCP |
|---|---|---|
| Reads (pages, quotes, previews, read tools) | 600/min | 120/min, burst 30 |
| Heavy reads (bars, portfolio history, leaderboard, profiles) | 120/min | 30/min |
| Order writes (place, replace, cancel, cancel all, close position) | 20/min and 300/day | 20/min and 300/day |
| Realtime | 5 connections per player, 60 live quote subscriptions each | — |

An idempotent replay and a preview never cost a write. Over a limit you get `RATE_LIMITED` (HTTP 429 with
`Retry-After`; MCP tools return `retry_after_s`).

## 10. Market data

What you see depends on the server's data profile (`GET /rules` → `dataSources`):

- **Live (production):** Alpaca Basic real-time **IEX** quotes and trades (a single exchange, about 2–3% of US volume;
  live 08:00–17:00 ET), consolidated SIP bars at least 15 minutes old, the calendar, assets, news and corporate actions;
  Finnhub real-time consolidated last trades (used for marks and trade-throughs), fundamentals and ETF types. Every
  quote shows its quality: real-time IEX, `SYNTHETIC` ("Est."), or `STALE`.
- **SIM (development):** simulated, deterministic prices; the app shows a **SIM** badge.

## 11. Notifications

You get a notification (bell, `GET /me/notifications`, MCP `get_notifications`, and live on the realtime socket) when:
an order fills or partially fills; an order expires; an order is cancelled for a reason you did not choose (admin,
freeze, reset, corporate action, not enough buying power at execution); the admin deposits, withdraws or adjusts your
cash (with the memo); you get a good-faith violation; your account is reset; your account is frozen or unfrozen.
