import { QueryClient, useQuery } from '@tanstack/react-query'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryKeys } from '@/lib/api/queryKeys'
import type { FeedItem, FeedReaction } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { ReactionBar } from './ReactionBar'

const bob = { handle: 'bob', displayName: 'Bob', kind: 'HUMAN' as const }
const demo = { handle: 'demo', displayName: 'Demo', kind: 'HUMAN' as const }

function item(reactions: FeedReaction[]): FeedItem {
  return { id: 'fde_1', at: '2026-10-09T15:00:00Z', kind: 'FILL', player: null, symbol: 'NVDA', payload: null, reactions } as FeedItem
}

/** Reads the item from the cached feed, as FeedList does, so optimistic updates re-render it. */
function Harness() {
  const q = useQuery<{ pages: Array<{ items: FeedItem[] }> }>({ queryKey: queryKeys.feed, queryFn: () => new Promise(() => {}), staleTime: Infinity })
  const i = q.data?.pages[0]?.items[0]
  return i ? <ReactionBar item={i} /> : null
}

function setup(reactions: FeedReaction[]) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  qc.setQueryData(queryKeys.me, { handle: 'demo', displayName: 'Demo', kind: 'HUMAN' })
  qc.setQueryData(queryKeys.feed, { pages: [{ items: [item(reactions)], nextCursor: null }], pageParams: [undefined] })
  renderWithRouter(<Harness />, qc)
  return qc
}

const answer = (reactions: FeedReaction[], status = 200) =>
  new Response(JSON.stringify(status === 200 ? { itemId: 'fde_1', reactions } : { code: 'INTERNAL_ERROR', title: 'x', status }), {
    status,
    headers: { 'Content-Type': status === 200 ? 'application/json' : 'application/problem+json' },
  })

describe('ReactionBar (§16.11)', () => {
  let fetchMock: ReturnType<typeof vi.fn<(r: Request) => Promise<Response>>>

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    fetchMock = vi.fn<(r: Request) => Promise<Response>>()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('joins an emoji optimistically, then shows the server answer', async () => {
    fetchMock.mockResolvedValue(answer([{ reaction: 'ROCKET', count: 2, players: [bob, demo] }]))
    setup([{ reaction: 'ROCKET', count: 1, players: [bob] }])
    const chip = await screen.findByRole('button', { name: 'Rocket 1' })
    expect(chip.getAttribute('aria-pressed')).toBe('false')
    expect(chip.getAttribute('title')).toBe('Bob')
    fireEvent.click(chip)
    const mine = await screen.findByRole('button', { name: 'Rocket 2, yours' })
    expect(mine.getAttribute('aria-pressed')).toBe('true')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const req = fetchMock.mock.calls[0]![0]
    expect(req.method).toBe('PUT')
    expect(new URL(req.url).pathname).toBe('/api/v1/feed/fde_1/reaction')
    expect(await req.json()).toEqual({ reaction: 'ROCKET' })
  })

  it('removes mine when I tap it again, and restores it when the server fails', async () => {
    fetchMock.mockResolvedValue(answer([], 500))
    setup([{ reaction: 'GEM', count: 1, players: [demo] }])
    fireEvent.click(await screen.findByRole('button', { name: 'Diamond hands 1, yours' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0]![0].method).toBe('DELETE')
    // the failed DELETE puts the reaction back
    expect(await screen.findByRole('button', { name: 'Diamond hands 1, yours' })).toBeTruthy()
  })

  it('picks a new emoji from the picker, moving mine', async () => {
    fetchMock.mockResolvedValue(answer([{ reaction: 'ROCKET', count: 1, players: [bob] }, { reaction: 'SKULL', count: 1, players: [demo] }]))
    setup([{ reaction: 'ROCKET', count: 2, players: [bob, demo] }])
    fireEvent.click(await screen.findByRole('button', { name: 'Add a reaction' }))
    fireEvent.click(screen.getByRole('button', { name: 'Skull' }))
    expect(await screen.findByRole('button', { name: 'Skull 1, yours' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Rocket 1' })).toBeTruthy()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  })
})
