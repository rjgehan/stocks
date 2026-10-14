import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import type { LeaderboardRow } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { FlowChip, LeaderboardTable } from './LeaderboardTable'

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
    ...rest,
  }
}

/** The desktop table (phones get a card list with the same content). */
const table = () => screen.getByRole('table', { name: 'Leaderboard' })
const rowOf = (name: string) => within(table()).getByText(name).closest('tr')!

describe('LeaderboardTable (§22.4, §26 leaderboard row rendering)', () => {
  it('renders ranked rows with return, P&L, vs SPY and the AI chip', async () => {
    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'alice' }), row({ handle: 'claude', kind: 'AI', rank: 2, return: '-0.0050', pnl: '-2.50' })]} me={null} metric="RETURN" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const alice = rowOf('Alice')
    expect(within(alice).getByLabelText('Rank 1')).toBeTruthy()
    expect(alice.textContent).toContain('+1.23%')
    expect(alice.textContent).toContain('+$6.15')
    expect(alice.textContent).toContain('+0.40%')
    const claude = rowOf('Claude')
    expect(within(claude).getByText('AI · Claude')).toBeTruthy()
    expect(claude.textContent).toContain('−0.50%')
    expect(claude.textContent).toContain('−$2.50')
  })

  it('greys out unranked rows and shows their eligibility note', async () => {
    renderWithRouter(<LeaderboardTable rows={[row({ handle: 'carol', rank: null, eligible: false, eligibilityNote: '3/5 trades' })]} me={null} metric="RETURN" />)
    await screen.findByRole('table', { name: 'Leaderboard' })
    const carol = rowOf('Carol')
    expect(carol.className).toContain('opacity-55')
    expect(carol.textContent).toContain('3/5 trades')
    expect(within(carol).queryByLabelText(/^Rank /)).toBeNull()
  })

  it('shows deposits as a chip, a missing equity as a dash (never "hidden": equity is public), and the rebased marker', async () => {
    renderWithRouter(
      <LeaderboardTable
        rows={[row({ handle: 'bob', netContributions: '600.00', flowsInPeriod: '100.00', equity: null, rebased: true })]}
        me={null}
        metric="PNL"
      />,
    )
    await screen.findByRole('table', { name: 'Leaderboard' })
    const bob = rowOf('Bob')
    expect(bob.textContent).toContain('$600.00')
    expect(within(bob).getByText(/\+\$100(\.00)? deposited/)).toBeTruthy()
    expect(within(bob).getByText('—')).toBeTruthy()
    expect(within(bob).queryByText(/hidden/i)).toBeNull()
    expect(within(bob).getByText('Rebased')).toBeTruthy()
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

describe('FlowChip', () => {
  it('says deposited or withdrawn, and hides for no flows', () => {
    const { rerender, container } = render(<FlowChip flows="250.00" />)
    expect(container.textContent).toBe('+$250 deposited')
    rerender(<FlowChip flows="-40.00" />)
    expect(container.textContent).toBe('−$40 withdrawn')
    rerender(<FlowChip flows="0.00" />)
    expect(container.textContent).toBe('')
    rerender(<FlowChip flows={null} />)
    expect(container.textContent).toBe('')
  })
})
