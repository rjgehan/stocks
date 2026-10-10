/**
 * Seed data for mock mode (§22.9). Built from the §18.4 example: a $500 CASH account that bought $125.00 of
 * NVDA (0.95274 sh @ 131.2000). Prices are fictional and tick in ten-thousandths (integers) in the fake market.
 */
import type { AssetSummary } from '../types'

export interface MockSymbol {
  symbol: string
  name: string
  exchange: string
  type: AssetSummary['type']
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
  { symbol: 'SIMA', name: 'Sim Alpha Corp (test symbol)', exchange: 'SIM', type: 'COMMON_STOCK', fractionable: true, prevClose: 4_212, openBps: 300, vol: 3, marketCap: null, peTtm: null, week52High: '0.9800', week52Low: '0.2100', industry: null, adv: 4_000_000 },
]

export const MOCK_INDEX_SYMBOLS = ['SPY', 'QQQ', 'DIA', 'IWM'] as const
export const POPULAR_SYMBOLS = ['AAPL', 'NVDA', 'MSFT', 'TSLA', 'AMZN'] as const

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

export const MOCK_PLAYERS = [
  { handle: 'claude', displayName: 'Claude', kind: 'AI' as const, aiModel: 'Claude', ret: '0.0412', pnl: '20.60', fills: 14, symbols: 6, top: 'NVDA' },
  { handle: 'alice', displayName: 'Alice', kind: 'HUMAN' as const, aiModel: null, ret: '0.0275', pnl: '13.75', fills: 9, symbols: 4, top: 'AAPL' },
  { handle: 'chatgpt', displayName: 'ChatGPT', kind: 'AI' as const, aiModel: 'GPT', ret: '0.0108', pnl: '5.40', fills: 11, symbols: 5, top: 'MSFT' },
  { handle: 'demo', displayName: 'Demo Player', kind: 'HUMAN' as const, aiModel: null, ret: '0.0031', pnl: '1.55', fills: 1, symbols: 1, top: 'NVDA' },
  { handle: 'bob', displayName: 'Bob', kind: 'HUMAN' as const, aiModel: null, ret: '-0.0164', pnl: '-8.20', fills: 6, symbols: 3, top: 'TSLA' },
]
