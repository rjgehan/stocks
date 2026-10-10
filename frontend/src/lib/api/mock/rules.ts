/**
 * `GET /rules` for mock mode (§18.3 `Rules`). The mock accepts every Phase 1 order type, so its matrix is the full
 * §11.1 table (the real backend publishes only the types its release accepts, `RulesService.AVAILABLE_ORDER_TYPES`).
 */
import type { Rules, Settings } from '../types'

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
      pnlDefinition: 'Net-contribution P&L: change in (equity − net deposits) over the period; it sums across resets.',
      pnlDayEnds: '20:00 ET (17:00 on early-close days)',
      minFills: settings['league.eligibility.minFills'],
      minSymbols: settings['league.eligibility.minSymbols'],
      ties: 'Equal returns (within 1e-8) rank by P&L, then by who opened their first account earlier.',
      benchmark: 'SPY',
      resetPolicy: 'A reset never erases a loss from any leaderboard period.',
    },
    social: { tradeFeedDelay: settings['social.tradeFeedDelay'], holdingsVisibility: settings['social.holdingsVisibility'] },
    dataSources: ['Mock market (simulated prices)'],
    fairness: ['Same rules for all.', 'Deposits are public.', 'No live copying of others.'],
  }
}
