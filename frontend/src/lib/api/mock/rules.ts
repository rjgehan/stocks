/**
 * `GET /rules` for mock mode (§18.3 `Rules`). The mock accepts every Phase 1 order type, so its matrix is the full
 * §11.1 table (the real backend publishes only the types its release accepts, `RulesService.AVAILABLE_ORDER_TYPES`).
 */
import type { Rules, Settings } from '../types'
import { CRYPTO_PAIRS } from '../../symbols'

export function mockRules(settings: Settings, now: string): Rules {
  return {
    version: 'mock-3',
    asOf: now,
    account: {
      type: 'CASH',
      startingCash: settings['league.defaultStartingCash'],
      settlement: 'T+1',
      pdtEnforced: false,
      shortSelling: false,
      buyingPowerFormula: 'cash − open buy holds (settled cash only under SETTLED_CASH_ONLY)',
      depositsSettleImmediately: true,
      gfv: { requiresAck: true, settledCashOnlyAt: 3, liquidationOnlyAt: 4, restrictionDays: 90, expiresAfter: 'P12M' },
    },
    sessions: {
      timezone: 'America/New_York',
      pre: '04:00–09:30',
      regular: '09:30–16:00',
      post: '16:00–20:00',
      earlyClose: '13:00 (after-hours until 17:00)',
      liveIexWindow: '08:00–17:00',
      overnight: false,
    },
    orders: {
      matrix: [
        {
          type: 'MARKET',
          sessions: 'REGULAR (entered outside it, queues for the next 09:30 ET open)',
          timeInForce: ['DAY'],
          qtyForms: ['SHARES', 'FRACTIONAL_SHARES', 'NOTIONAL'],
          notes: 'Fills at the touch within a 5% collar.',
        },
        {
          type: 'LIMIT',
          sessions: 'REGULAR; also PRE and POST when extendedHours=true',
          timeInForce: ['DAY', 'GTC'],
          qtyForms: ['SHARES'],
          notes: 'Marketable on arrival: fills at the touch. Resting: fills at the limit. GTC lasts 60 days.',
        },
        {
          type: 'STOP',
          sessions: 'REGULAR only',
          timeInForce: ['DAY', 'GTC'],
          qtyForms: ['SHARES'],
          notes: 'Triggers on the last eligible consolidated trade, then becomes a market order with the collar.',
        },
        { type: 'STOP_LIMIT', sessions: 'REGULAR only', timeInForce: ['DAY', 'GTC'], qtyForms: ['SHARES'], notes: 'Triggers like STOP, then becomes a limit order.' },
        {
          type: 'TRAILING_STOP',
          sessions: 'REGULAR only, and can only be entered during REGULAR',
          timeInForce: ['DAY'],
          qtyForms: ['SHARES'],
          notes: 'Exactly one of trailAmount ($) or trailPercent (0 < p < 100).',
        },
      ],
      gtcDays: 60,
      maxOpenOrders: 50,
      maxQty: '200000',
      tick: '$0.01 at or above $1.00, $0.0001 below',
      collarPct: '5',
      holdPctRegular: '102',
      holdPctQueued: '115',
      fatFingerWarnPct: '5',
      priceTooFarPct: '50',
      clientOrderIdPattern: '^[A-Za-z0-9._:-]{1,64}$',
      rationaleRequiredForAi: settings['bots.requireRationale'],
      fractional: { minBuyNotional: '1.00', minSellNotional: '0.01', minQty: '0.00001', marketOnly: true, dayOnly: true, rthOnly: true, replaceable: false },
      // Phase 2 (§11.9, §11.10, §14)
      groups: [
        { type: 'BRACKET', legs: 'entry (BUY) + takeProfit (SELL LIMIT) + stopLoss (SELL STOP or STOP_LIMIT)', entryTypes: ['MARKET', 'LIMIT'], timeInForce: ['DAY', 'GTC'], notes: 'Take profit > entry > stop loss, at least 0.1% apart.' },
        { type: 'OCO', legs: 'takeProfit (LIMIT) + stopLoss (STOP or STOP_LIMIT)', entryTypes: [], timeInForce: ['DAY', 'GTC'], notes: 'The first execution cancels the other.' },
        { type: 'OTO', legs: 'entry (BUY) + secondary (SELL)', entryTypes: ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'], timeInForce: ['DAY', 'GTC'], notes: 'The secondary waits for the entry.' },
        { type: 'OTOCO', legs: 'entry (BUY) + takeProfit + stopLoss', entryTypes: ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'], timeInForce: ['DAY', 'GTC'], notes: 'An OTO whose secondary is an OCO pair.' },
      ],
      cancelLatency: null,
      dividendAdjustment: 'Open buy limits and sell stops are reduced by the dividend on the ex-date (FINRA 5330).',
    },
    execution: {
      latency: '250 ms + 100–500 ms of seeded jitter',
      qualityLadder: ['SIMULATED (mock mode)'],
      freshness: {},
      partialFills: 'Mock mode fills in one piece.',
      liquiditySharedAcrossPlayers: false,
    },
    fees: [
      { code: 'SEC', appliesTo: 'SELL', basis: 'PRINCIPAL', rate: '0.0000206', rounding: 'CEIL_CENT', maxPerOrder: null, effectiveFrom: '2026-04-04', effectiveTo: null, note: 'SEC Section 31' },
      { code: 'TAF', appliesTo: 'SELL', basis: 'SHARES', rate: '0', rounding: 'HALF_UP_CENT', maxPerOrder: null, effectiveFrom: '2026-10-01', effectiveTo: '2026-12-31', note: 'FINRA TAF (paused)' },
      { code: 'CAT', appliesTo: 'BOTH', basis: 'SHARES', rate: '0.000003', rounding: 'SUBCENT_ZERO', maxPerOrder: null, effectiveFrom: '2026-01-01', effectiveTo: null, note: 'Consolidated Audit Trail' },
    ],
    rateLimits: { scope: 'Per player (mock mode does not limit)' },
    leaderboard: {
      returnDefinition: 'Time-weighted return from unit values (TWR = U(end) / U(start) − 1): deposits, withdrawals and resets never move it.',
      pnlDefinition: 'Net-contribution P&L: change in (equity − net deposits) over the period; it sums across resets. The league ranks players by all-time P&L.',
      pnlDayEnds: '20:00 ET (17:00 on early-close days)',
      minFills: settings['league.eligibility.minFills'],
      minSymbols: settings['league.eligibility.minSymbols'],
      ties: 'Equal returns (within 1e-8) rank by P&L, then by who opened their first account earlier.',
      benchmark: 'SPY',
      resetPolicy: 'A reset never erases a loss from any leaderboard period.',
    },
    social: { tradeFeedDelay: settings['social.tradeFeedDelay'], visibility: 'PUBLIC' },
    // §29: 24/7 crypto pairs (admin setting crypto.enabled)
    crypto: {
      enabled: settings['crypto.enabled'],
      pairs: [...CRYPTO_PAIRS],
      hours: 'Around the clock, 7 days a week, holidays included, while crypto.enabled is on (off: buys are refused, sells still work).',
      symbols: 'Canonical BTC/USD in JSON and query strings; BTC-USD in URL paths and STOMP topics.',
      quantities: 'Up to 5 decimals on every order type (GTC included); dollar (notional) amounts on MARKET orders.',
      orders: 'Every order type at any hour; extendedHours is ignored; a DAY order lasts until 24:00 ET.',
      settlement: 'Immediate: no unsettled cash, no good-faith violations.',
      fees: 'None (no SEC, TAF or CAT fee).',
      pnlDays: 'Every calendar day is a P&L day while crypto is on (weekends included).',
    },
    // §30: long calls and puts (admin setting options.enabled); the mock prices them off its own random walks
    options: {
      enabled: settings['options.enabled'],
      feed: 'simulated',
      quotesIndicative: false,
      multiplier: 100,
      scope: 'Long US equity options only: BUY to open and SELL to close calls and puts. No short options, spreads or exercise.',
      symbols: 'OCC: root + YYMMDD + C|P + strike × 1000 in 8 digits (AAPL261120C00250000 = AAPL 2026-11-20 $250 call).',
      hours: 'Regular session only (09:30–16:00 ET); buys are refused while options.enabled is off (sells still work).',
      quantities: 'Whole contracts of 100 shares; prices are per share, dollar amounts include the × 100.',
      orders: 'BUY opens, SELL closes (no short options, spreads or exercise). MARKET or LIMIT (at most 2 decimals), DAY only, no extended hours, no notional. Buys fill at the ask, sells at the bid.',
      settlement: 'T+1 for sale proceeds.',
      fees: 'None.',
      execution: 'Buys fill at the ask, sells at the bid, in one piece; no fill without a two-sided quote or when the spread is wider than $0.10 and more than 50% of the mid.',
      valuation: 'Marked at the mid (the bid without an ask) × 100 × contracts.',
      expiration: 'At the close on expiration day an in-the-money contract cash-settles at its intrinsic value from the underlying close; an out-of-the-money one expires worthless (a SYSTEM sell with liquidity EXPIRY).',
    },
    dataSources: ['Mock market (simulated prices)'],
    // the §16.7 public mode charter (RulesService.fairness), the "rationale required" variant
    fairness: [
      'Same rules for all: the same starting cash, market data, latency, fill rules, fees and write limits on every channel, for humans and bots alike. Players never trade with each other.',
      "Deposits are public: net contributions and flows are shown on every leaderboard row, and admin deposits never move anyone's return. Resets are counted publicly, and a reset never erases a loss.",
      "Everything is public: every player's live portfolio (cash, positions, open orders), full order history with fills, account activity, P&L analysis, rationales, journal and stats are visible to every player in real time. The feed delay (none by default) only holds back the feed, the trade list and journal notes.",
      "Rationales: every new order's rationale or note is public, and AI players must give one. Notes written before public mode keep the visibility their author chose.",
      'Kill switch: the admin can freeze any player, and every such action is audited. Bots can never act as admin.',
      'Secrets stay private: passwords, API keys, OAuth tokens, email addresses, sessions and the audit log.',
    ],
  }
}
