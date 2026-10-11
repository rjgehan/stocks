import { describe, expect, it } from 'vitest'
import {
  channelLabel,
  formatDuration,
  formatTokens,
  formatUsd,
  outcomeTone,
  runSummaryLine,
  spendMeter,
  statusLabel,
  statusTone,
} from './agentView'

describe('agent run statuses', () => {
  it('labels every status and keeps color off the up/down palette for problems', () => {
    expect(statusLabel('COMPLETED')).toBe('Completed')
    expect(statusLabel('SKIPPED_NON_TRADING_DAY')).toBe('Skipped: market closed')
    expect(statusLabel(null)).toBe('No runs yet')
    expect(statusTone('COMPLETED')).toBe('accent')
    expect(statusTone('DAILY_CAP')).toBe('warn')
    expect(statusTone('ERROR')).toBe('down')
    expect(statusTone('SKIPPED_BUSY')).toBe('neutral')
  })

  it('summarizes the last run for the profile card', () => {
    const base = { handle: 'claude', runs: 12, runsLast7Days: 5, lastRunAt: '2026-10-09T13:45:00Z' }
    expect(runSummaryLine({ ...base, lastStatus: 'COMPLETED', lastRunOrders: 2, lastRunToolCalls: 9 })).toBe('Completed · 9 tool calls · 2 orders')
    expect(runSummaryLine({ ...base, lastStatus: 'COMPLETED', lastRunOrders: 1, lastRunToolCalls: 1 })).toBe('Completed · 1 tool call · 1 order')
    expect(runSummaryLine({ ...base, lastStatus: 'SKIPPED_NON_TRADING_DAY', lastRunOrders: 0, lastRunToolCalls: 0 })).toBe('Skipped: market closed')
    expect(runSummaryLine({ ...base, runs: 0, lastStatus: null, lastRunOrders: null, lastRunToolCalls: null })).toBe('No runs reported yet')
  })
})

describe('the daily spend meter', () => {
  it('is amber from 80 % and red at the cap, with the amounts in text', () => {
    expect(spendMeter('0.42', '1.000000')).toEqual({ pct: 42, tone: 'ok', label: '$0.42 of $1.00' })
    expect(spendMeter('0.80', '1.00').tone).toBe('warn')
    expect(spendMeter('1.000000', '1.00')).toMatchObject({ pct: 100, tone: 'over' })
    expect(spendMeter('1.37', '1.00')).toMatchObject({ pct: 100, tone: 'over', label: '$1.37 of $1.00' })
    expect(spendMeter('0.003', null)).toEqual({ pct: 0, tone: 'none', label: '$0.0030 today' })
    expect(spendMeter(null, '0')).toMatchObject({ tone: 'none', label: '$0.00 today' })
  })
})

describe('formatting', () => {
  it('formats durations, tokens and costs', () => {
    expect(formatDuration(450)).toBe('450 ms')
    expect(formatDuration(9_400)).toBe('9.4 s')
    expect(formatDuration(94_000)).toBe('1 min 34 s')
    expect(formatDuration(120_000)).toBe('2 min')
    expect(formatDuration(7_500_000)).toBe('2 h 5 min')
    expect(formatDuration(null)).toBe('--')
    expect(formatTokens(950)).toBe('950')
    expect(formatTokens(12_400)).toBe('12k')
    expect(formatTokens(5_210)).toBe('5.2k')
    expect(formatTokens(1_250_000)).toBe('1.3M')
    expect(formatUsd('0.183400')).toBe('$0.18')
    expect(formatUsd('0.003400')).toBe('$0.0034')
    expect(formatUsd('0')).toBe('$0.00')
    expect(formatUsd(null)).toBe('--')
  })

  it('tones API call outcomes without relying on color alone', () => {
    expect(outcomeTone({ ok: true, outcome: '200' })).toBe('neutral')
    expect(outcomeTone({ ok: false, outcome: '429' })).toBe('warn')
    expect(outcomeTone({ ok: false, outcome: 'FORBIDDEN_SCOPE' })).toBe('warn')
    expect(outcomeTone({ ok: false, outcome: '500' })).toBe('down')
    expect(outcomeTone({ ok: false, outcome: 'INTERNAL_ERROR' })).toBe('down')
    expect(channelLabel('MCP_KEY')).toBe('MCP')
    expect(channelLabel('REST_KEY')).toBe('REST')
  })
})
