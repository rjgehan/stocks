/**
 * Seed data for mock mode (§22.9). Built from the §18.4 example: a $500 CASH account that bought $125.00 of
 * NVDA (0.95274 sh @ 131.2000). Prices are fictional and tick in ten-thousandths (integers) in the fake market. The 12
 * crypto pairs (§29) trade 24/7; their `adv` is in coins and their prices below $1 keep 4 decimals.
 */
import type { AssetSummary } from '../types'

export interface MockSymbol {
  symbol: string
  name: string
  exchange: string
  type: NonNullable<AssetSummary['type']>
  fractionable: boolean
  /** previous close in ten-thousandths of a dollar */
  prevClose: number
  /** opening move vs previous close, in basis points */
  openBps: number
  /** relative volatility of the random walk (1 = typical large cap) */
  vol: number
  marketCap: string | null
  peTtm: string | null
  week52High: string
  week52Low: string
  industry: string | null
  adv: number
}

export const MOCK_UNIVERSE: MockSymbol[] = [
  { symbol: 'SPY', name: 'SPDR S&P 500 ETF Trust', exchange: 'ARCA', type: 'ETF', fractionable: true, prevClose: 6_682_100, openBps: 18, vol: 0.5, marketCap: null, peTtm: null, week52High: '689.7000', week52Low: '508.4600', industry: null, adv: 61_000_000 },
  { symbol: 'QQQ', name: 'Invesco QQQ Trust', exchange: 'NASDAQ', type: 'ETF', fractionable: true, prevClose: 6_015_400, openBps: 31, vol: 0.7, marketCap: null, peTtm: null, week52High: '612.1200', week52Low: '402.3900', industry: null, adv: 42_000_000 },
  { symbol: 'DIA', name: 'SPDR Dow Jones Industrial Average ETF', exchange: 'ARCA', type: 'ETF', fractionable: true, prevClose: 4_651_800, openBps: -6, vol: 0.45, marketCap: null, peTtm: null, week52High: '472.5500', week52Low: '366.3200', industry: null, adv: 3_400_000 },
  { symbol: 'IWM', name: 'iShares Russell 2000 ETF', exchange: 'ARCA', type: 'ETF', fractionable: true, prevClose: 2_446_300, openBps: -22, vol: 0.9, marketCap: null, peTtm: null, week52High: '251.8700', week52Low: '171.7300', industry: null, adv: 29_000_000 },
  { symbol: 'AAPL', name: 'Apple Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 2_243_700, openBps: 95, vol: 1, marketCap: '3420000000000', peTtm: '34.12', week52High: '260.1000', week52Low: '169.2100', industry: 'Technology', adv: 52_000_000 },
  { symbol: 'NVDA', name: 'NVIDIA Corporation', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 1_289_900, openBps: 120, vol: 1.6, marketCap: '3190000000000', peTtm: '52.80', week52High: '153.1300', week52Low: '86.6200', industry: 'Semiconductors', adv: 210_000_000 },
  { symbol: 'MSFT', name: 'Microsoft Corporation', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 5_121_600, openBps: -40, vol: 0.9, marketCap: '3810000000000', peTtm: '37.64', week52High: '555.4500', week52Low: '344.7900', industry: 'Software', adv: 21_000_000 },
  { symbol: 'TSLA', name: 'Tesla, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 4_350_900, openBps: -185, vol: 2.4, marketCap: '1400000000000', peTtm: '176.30', week52High: '488.5400', week52Low: '214.2500', industry: 'Automobiles', adv: 98_000_000 },
  { symbol: 'AMZN', name: 'Amazon.com, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 2_211_800, openBps: 44, vol: 1.1, marketCap: '2360000000000', peTtm: '33.95', week52High: '242.5200', week52Low: '161.3800', industry: 'Retail', adv: 41_000_000 },
  { symbol: 'META', name: 'Meta Platforms, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 7_124_500, openBps: 66, vol: 1.3, marketCap: '1790000000000', peTtm: '27.41', week52High: '796.2500', week52Low: '479.8000', industry: 'Interactive Media', adv: 12_000_000 },
  { symbol: 'AMD', name: 'Advanced Micro Devices, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 1_650_200, openBps: -73, vol: 1.9, marketCap: '268000000000', peTtm: '96.10', week52High: '186.6500', week52Low: '76.4800', industry: 'Semiconductors', adv: 47_000_000 },
  { symbol: 'GOOGL', name: 'Alphabet Inc. Class A', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 2_451_200, openBps: 21, vol: 1.1, marketCap: '2970000000000', peTtm: '26.20', week52High: '256.0000', week52Low: '142.6600', industry: 'Interactive Media', adv: 33_000_000 },
  { symbol: 'NFLX', name: 'Netflix, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 12_041_000, openBps: -52, vol: 1.4, marketCap: '512000000000', peTtm: '51.30', week52High: '1341.1500', week52Low: '677.8800', industry: 'Entertainment', adv: 3_900_000 },
  { symbol: 'JPM', name: 'JPMorgan Chase & Co.', exchange: 'NYSE', type: 'COMMON_STOCK', fractionable: true, prevClose: 3_067_800, openBps: 12, vol: 0.7, marketCap: '843000000000', peTtm: '15.40', week52High: '318.0100', week52Low: '202.1600', industry: 'Banks', adv: 9_200_000 },
  { symbol: 'KO', name: 'The Coca-Cola Company', exchange: 'NYSE', type: 'COMMON_STOCK', fractionable: true, prevClose: 682_300, openBps: -9, vol: 0.4, marketCap: '294000000000', peTtm: '24.10', week52High: '74.3800', week52Low: '60.6200', industry: 'Beverages', adv: 14_000_000 },
  { symbol: 'DIS', name: 'The Walt Disney Company', exchange: 'NYSE', type: 'COMMON_STOCK', fractionable: true, prevClose: 1_132_500, openBps: 34, vol: 0.9, marketCap: '204000000000', peTtm: '18.70', week52High: '124.6900', week52Low: '80.1000', industry: 'Entertainment', adv: 8_700_000 },
  { symbol: 'COIN', name: 'Coinbase Global, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 3_384_000, openBps: 240, vol: 3, marketCap: '86000000000', peTtm: '31.80', week52High: '444.6500', week52Low: '142.5800', industry: 'Financial Services', adv: 11_000_000 },
  { symbol: 'PLTR', name: 'Palantir Technologies Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 1_793_400, openBps: 160, vol: 2.6, marketCap: '424000000000', peTtm: '590.00', week52High: '207.5200', week52Low: '41.6200', industry: 'Software', adv: 64_000_000 },
  { symbol: 'F', name: 'Ford Motor Company', exchange: 'NYSE', type: 'COMMON_STOCK', fractionable: true, prevClose: 118_400, openBps: -35, vol: 1.2, marketCap: '47000000000', peTtm: '14.20', week52High: '12.4600', week52Low: '8.4400', industry: 'Automobiles', adv: 58_000_000 },
  { symbol: 'SOFI', name: 'SoFi Technologies, Inc.', exchange: 'NASDAQ', type: 'COMMON_STOCK', fractionable: true, prevClose: 262_100, openBps: 88, vol: 2.2, marketCap: '29000000000', peTtm: '53.10', week52High: '29.5000', week52Low: '8.6000', industry: 'Financial Services', adv: 70_000_000 },
  { symbol: 'BRK.B', name: 'Berkshire Hathaway Inc. Class B', exchange: 'NYSE', type: 'COMMON_STOCK', fractionable: true, prevClose: 4_891_200, openBps: 4, vol: 0.4, marketCap: '1050000000000', peTtm: '16.80', week52High: '542.0700', week52Low: '440.1000', industry: 'Insurance', adv: 4_100_000 },
  { symbol: 'XYZW', name: 'Whole-Share Only Corp (mock, not fractionable)', exchange: 'NYSE', type: 'COMMON_STOCK', fractionable: false, prevClose: 412_200, openBps: 12, vol: 1, marketCap: '3100000000', peTtm: '12.40', week52High: '48.2000', week52Low: '30.1000', industry: 'Industrials', adv: 900_000 },
  // §29 crypto pairs: canonical BTC/USD (BTC-USD in URL paths and STOMP topics)
  { symbol: 'BTC/USD', name: 'Bitcoin / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 1_120_000_000, openBps: 45, vol: 1.8, marketCap: null, peTtm: null, week52High: '124450.0000', week52Low: '74420.0000', industry: null, adv: 35_000 },
  { symbol: 'ETH/USD', name: 'Ethereum / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 42_000_000, openBps: 70, vol: 2.2, marketCap: null, peTtm: null, week52High: '4950.0000', week52Low: '1385.0000', industry: null, adv: 420_000 },
  { symbol: 'SOL/USD', name: 'Solana / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 2_100_000, openBps: -90, vol: 2.8, marketCap: null, peTtm: null, week52High: '295.8300', week52Low: '95.2600', industry: null, adv: 3_100_000 },
  { symbol: 'XRP/USD', name: 'XRP / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 26_000, openBps: 35, vol: 2.6, marketCap: null, peTtm: null, week52High: '3.6600', week52Low: '0.4900', industry: null, adv: 900_000_000 },
  { symbol: 'DOGE/USD', name: 'Dogecoin / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 2_400, openBps: -120, vol: 3.2, marketCap: null, peTtm: null, week52High: '0.4800', week52Low: '0.0980', industry: null, adv: 2_000_000_000 },
  { symbol: 'LTC/USD', name: 'Litecoin / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 1_050_000, openBps: 20, vol: 2.2, marketCap: null, peTtm: null, week52High: '146.2000', week52Low: '62.7500', industry: null, adv: 4_000_000 },
  { symbol: 'BCH/USD', name: 'Bitcoin Cash / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 5_400_000, openBps: -15, vol: 2.1, marketCap: null, peTtm: null, week52High: '640.5000', week52Low: '252.1000', industry: null, adv: 300_000 },
  { symbol: 'LINK/USD', name: 'Chainlink / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 220_000, openBps: 110, vol: 2.9, marketCap: null, peTtm: null, week52High: '30.9400', week52Low: '9.8700', industry: null, adv: 20_000_000 },
  { symbol: 'AVAX/USD', name: 'Avalanche / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 300_000, openBps: -60, vol: 3, marketCap: null, peTtm: null, week52High: '55.0800', week52Low: '14.6500', industry: null, adv: 15_000_000 },
  { symbol: 'DOT/USD', name: 'Polkadot / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 42_000, openBps: -45, vol: 2.8, marketCap: null, peTtm: null, week52High: '11.5500', week52Low: '3.1200', industry: null, adv: 40_000_000 },
  { symbol: 'UNI/USD', name: 'Uniswap / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 85_000, openBps: 80, vol: 3, marketCap: null, peTtm: null, week52High: '19.4200', week52Low: '4.7800', industry: null, adv: 12_000_000 },
  { symbol: 'AAVE/USD', name: 'Aave / US Dollar', exchange: 'CRYPTO', type: 'CRYPTO', fractionable: true, prevClose: 2_800_000, openBps: 140, vol: 3, marketCap: null, peTtm: null, week52High: '399.8700', week52Low: '118.4000', industry: null, adv: 500_000 },
  { symbol: 'SIMA', name: 'Sim Alpha Corp (test symbol)', exchange: 'SIM', type: 'COMMON_STOCK', fractionable: true, prevClose: 4_212, openBps: 300, vol: 3, marketCap: null, peTtm: null, week52High: '0.9800', week52Low: '0.2100', industry: null, adv: 4_000_000 },
]

export const MOCK_INDEX_SYMBOLS = ['SPY', 'QQQ', 'DIA', 'IWM'] as const
export const POPULAR_SYMBOLS = ['AAPL', 'NVDA', 'MSFT', 'TSLA', 'AMZN', 'BTC/USD', 'ETH/USD'] as const

export const MOCK_ME_BASE = {
  playerId: 'ply_01J9Y4N2QK7D3X8M5R6T1V0WZB',
  handle: 'demo',
  displayName: 'Demo Player',
  kind: 'HUMAN' as const,
  aiModel: null,
  role: 'ADMIN' as const,
  status: 'ACTIVE' as const,
  accountId: 'acc_01J9Y4N2R0S8F6E2W3C9H7K5PA',
  runNo: 1,
  authMethod: 'SESSION' as const,
  scopes: ['market:read', 'account:read', 'trade', 'league:read'],
  createdAt: '2026-10-01T13:00:00.000Z',
}

/**
 * The other league members. `u` is their current unit value (TWR = U − 1 since the season start, §16.2) and
 * `nc` their net contributions; equity = units × U, so a deposit raises units and equity but never U.
 */
export const MOCK_PLAYERS = [
  { handle: 'claude', displayName: 'Claude', kind: 'AI' as const, aiModel: 'Claude', u: '1.0412', nc: '500.00', fills: 14, symbols: 6, top: 'NVDA', owner: 'demo' },
  { handle: 'alice', displayName: 'Alice', kind: 'HUMAN' as const, aiModel: null, u: '1.0275', nc: '500.00', fills: 9, symbols: 4, top: 'AAPL', owner: null },
  { handle: 'chatgpt', displayName: 'ChatGPT', kind: 'AI' as const, aiModel: 'GPT', u: '1.0108', nc: '500.00', fills: 11, symbols: 5, top: 'MSFT', owner: 'demo' },
  { handle: 'bob', displayName: 'Bob', kind: 'HUMAN' as const, aiModel: null, u: '0.9836', nc: '500.00', fills: 6, symbols: 3, top: 'TSLA', owner: null },
  { handle: 'carol', displayName: 'Carol', kind: 'HUMAN' as const, aiModel: null, u: '1.0021', nc: '500.00', fills: 3, symbols: 1, top: 'SPY', owner: null },
]

/** An always-valid invite for trying the signup flow in mock mode: /join/welcome-friends */
export const MOCK_INVITE_CODE = 'welcome-friends'

/**
 * Public mode (§16.7): what each member holds and trades, so their Portfolio and History screens have something to
 * show. `holdings` split the money they have invested (the first is their `top`); the server prices them from the
 * fake market at seed time. `closed` is a position they bought and sold again (a realized gain or loss), `working`
 * a resting limit buy below the market, `cancelled` an order they pulled. One bot also has a bracket whose entry has
 * not filled yet, so its take profit and stop loss are held. Bots explain every order; humans only some.
 */
export interface MockPlayerBook {
  holdings: Array<{ symbol: string; share: number }>
  closed: { symbol: string; pnl: string }
  working: string
  cancelled: string
  bracket?: string
  notes: { buy: string | null; closed: string | null; working: string | null }
}

export const MOCK_BOOKS: Record<string, MockPlayerBook> = {
  claude: {
    holdings: [
      { symbol: 'NVDA', share: 0.5 },
      { symbol: 'AMD', share: 0.3 },
      { symbol: 'MSFT', share: 0.2 },
    ],
    closed: { symbol: 'META', pnl: '2.15' },
    working: 'KO',
    cancelled: 'TSLA',
    bracket: 'GOOGL',
    notes: {
      buy: 'Thesis: guidance raised and the spread is tight; a starter position with a limit under the last print, keeping 60% settled cash for GFV-safe exits.',
      closed: 'Took the gain after a 5% run; the setup played out and the cash settles tomorrow.',
      working: 'Bid below the 20-day average: only worth owning on a pullback.',
    },
  },
  alice: {
    holdings: [
      { symbol: 'AAPL', share: 0.6 },
      { symbol: 'SPY', share: 0.4 },
    ],
    closed: { symbol: 'DIS', pnl: '-1.40' },
    working: 'KO',
    cancelled: 'NFLX',
    notes: { buy: 'Core holding, buying the dip.', closed: null, working: null },
  },
  chatgpt: {
    holdings: [
      { symbol: 'MSFT', share: 0.4 },
      { symbol: 'GOOGL', share: 0.25 },
      { symbol: 'AAPL', share: 0.2 },
      { symbol: 'KO', share: 0.15 },
    ],
    closed: { symbol: 'TSLA', pnl: '3.05' },
    working: 'JPM',
    cancelled: 'AMD',
    notes: {
      buy: 'Rebalancing toward large caps: the league leader is overweight semis, so diversification is my edge this week.',
      closed: 'Took profit on TSLA after a 6% run; volatility too high for the rest of the week.',
      working: 'Banks look cheap against the index; a limit a little under the bid.',
    },
  },
  bob: {
    holdings: [
      { symbol: 'TSLA', share: 0.7 },
      { symbol: 'F', share: 0.3 },
    ],
    closed: { symbol: 'COIN', pnl: '-2.60' },
    working: 'F',
    cancelled: 'SOFI',
    notes: { buy: null, closed: 'Cut it. Lesson learned.', working: null },
  },
  carol: {
    holdings: [
      { symbol: 'SPY', share: 0.8 },
      { symbol: 'KO', share: 0.2 },
    ],
    closed: { symbol: 'QQQ', pnl: '0.85' },
    working: 'KO',
    cancelled: 'IWM',
    notes: { buy: null, closed: null, working: 'Adding on any red day.' },
  },
}
