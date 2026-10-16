import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import type { LeaderboardRow } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import type { BenchmarkPlayer } from '@/lib/api/types'
import { LeaderboardTable, benchmarkIndex } from './LeaderboardTable'

const spy: BenchmarkPlayer = { symbol: 'SPY', startingCash: '500000.00', equity: '510750.00', return: '0.0080', pnl: '4053.57', dayReturn: '0.0021', dayPnl: '1070.50', totalReturn: '0.0215', totalPnl: '10750.00' }

function row(over: Partial<LeaderboardRow> & { handle: string; kind?: 'HUMAN' | 'AI' }): LeaderboardRow {
  const { handle, kind = 'HUMAN', ...rest } = over
  return {
    rank: 1,
    rankChange: null,
    eligible: true,
    eligibilityNote: null,
    partialPeriod: false,
    rebased: false,
    player: {
      playerId: `ply_${handle}`,
      handle,
      displayName: handle.charAt(0).toUpperCase() + handle.slice(1),
      kind,
      aiModel: kind === 'AI' ? 'Claude' : null,
      avatarSeed: null,
      memberSince: '2026-10-01T13:00:00Z',
      resets: 0,
    },
    apiTrader: false,
    return: '0.0123',
    pnl: '6.15',
    excessVsBenchmark: '0.0040',
    maxDrawdown: '0.0100',
    equity: '506.15',
    netContributions: '500.00',
    flowsInPeriod: '0.00',
    fills: 7,
    symbolsTraded: 3,
    winRate: null,
    topHolding: 'NVDA',
    spark: ['1', '1.01', '1.0123'],
    sharpe: null,
    profitFactor: null,
    dayReturn: '0.0020',
    dayPnl: '1.00',
    totalReturn: '0.0300',
    totalPnl: '15.00',
    ...rest,
  }
}

/** The desktop table (phones get a card list with the same content). */
const table = () => screen.getByRole('table', { name: 'Leaderboard' })
const rowOf = (name: string) => within(table()).getByText(name).closest('tr')!

describe('LeaderboardTable (§22.4, §26 leaderboard row rendering)', () => {
  it('renders ranked rows with their return and the AI chip', async () => {
    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'alice' }), row({ handle: 'claude', kind: 'AI', rank: 2, return: '-0.0050', pnl: '-2.50' })]} me={null} metric="RETURN" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const alice = rowOf('Alice')
    expect(within(alice).getByLabelText('Rank 1')).toBeTruthy()
    expect(alice.textContent).toContain('+1.23%')
    const claude = rowOf('Claude')
    expect(within(claude).getByText('AI · Claude')).toBeTruthy()
    expect(claude.textContent).toContain('−0.50%')
  })

  it('greys out unranked rows and shows their eligibility note', async () => {
    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'carol', rank: null, eligible: false, eligibilityNote: '3/5 trades' })]} me={null} metric="RETURN" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const carol = rowOf('Carol')
    expect(carol.className).toContain('opacity-55')
    expect(carol.textContent).toContain('3/5 trades')
    expect(within(carol).queryByLabelText(/^Rank /)).toBeNull()
  })

  it('shows a missing equity as a dash (never "hidden": equity is public) and the rebased marker, with no deposit columns', async () => {
    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'bob', netContributions: '600.00', flowsInPeriod: '100.00', equity: null, rebased: true })]} me={null} metric="PNL" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const bob = rowOf('Bob')
    expect(within(bob).getByText('—')).toBeTruthy()
    expect(within(bob).queryByText(/hidden/i)).toBeNull()
    expect(within(bob).getByText('Rebased')).toBeTruthy()
    expect(bob.textContent).not.toContain('deposited')
    expect(within(table()).queryByText(/Net contrib/i)).toBeNull()
    expect(within(table()).queryByText(/vs SPY/i)).toBeNull()
  })

  it('shows the period, Day and Total as % or $ by the metric', async () => {
    const { unmount } = renderWithRouter(<LeaderboardTable rows={[row({ handle: 'alice' })]} me={null} metric="RETURN" period="WEEK" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const heads = within(table()).getAllByRole('columnheader').map((h) => h.textContent)
    expect(heads.slice(2, 5)).toEqual(['Week', 'Day', 'Total'])
    const pct = rowOf('Alice').textContent
    expect(pct).toContain('+1.23%')
    expect(pct).toContain('+0.20%')
    expect(pct).toContain('+3.00%')
    expect(pct).not.toContain('$6.15')
    unmount()

    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'alice' })]} me={null} metric="PNL" period="TODAY" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    expect(within(table()).getAllByRole('columnheader').map((h) => h.textContent).slice(2, 4)).toEqual(['Day', 'Total'])
    const usd = rowOf('Alice').textContent
    expect(usd).toContain('+$1.00')
    expect(usd).toContain('+$15.00')
    expect(usd).not.toContain('%')
  })

  it('slots SPY in unranked, after the ranked rows that beat it', async () => {
    const rows = [row({ handle: 'alice', return: '0.0300' }), row({ handle: 'bob', rank: 2, return: '0.0010' }), row({ handle: 'carol', rank: null, eligible: false, return: '0.0500' })]
    expect(benchmarkIndex(rows, spy, 'RETURN')).toBe(1)
    expect(benchmarkIndex(rows, { ...spy, return: '0.0900' }, 'RETURN')).toBe(0)
    expect(benchmarkIndex(rows, { ...spy, return: '-0.0100' }, 'RETURN')).toBe(2)
    expect(benchmarkIndex(rows, { ...spy, return: null }, 'RETURN')).toBe(-1)
    renderWithRouter(<LeaderboardTable rows={rows} me={null} metric="RETURN" benchmark={spy} />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const lines = within(table()).getAllByRole('row').slice(1)
    expect(lines[1]!.getAttribute('aria-label')).toBe('Benchmark')
    expect(lines[1]!.textContent).toContain('S&P 500')
    expect(lines[1]!.textContent).toContain('+0.80%')
    expect(within(lines[1]!).queryByLabelText(/^Rank /)).toBeNull()
  })

  it('pins my row at the bottom only when the board does not list it', async () => {
    const me = row({ handle: 'demo', rank: 12 })
    const { unmount } = renderWithRouter(<LeaderboardTable rows={[row({ handle: 'alice' })]} me={me} metric="RETURN" myHandle="demo" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const rows = within(table()).getAllByRole('row')
    expect(rows.at(-1)!.textContent).toContain('Demo')
    expect(within(rows.at(-1)!).getByText('You')).toBeTruthy()
    unmount()

    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'alice' }), me]} me={me} metric="RETURN" myHandle="demo" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    expect(within(table()).getAllByText('Demo')).toHaveLength(1)
  })
})
