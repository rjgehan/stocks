import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRootRoute, createRouter } from '@tanstack/react-router'
import { MockServer } from '@/lib/api/mock/server'
import type { CreatePlayerResponse } from '@/lib/api/types'
import { CreatePlayerDialog } from './CreatePlayerDialog'

const SLOW = { timeout: 5000 }
let server: MockServer
let posted: unknown[]

beforeEach(() => {
  sessionStorage.clear()
  server = new MockServer()
  posted = []
  vi.stubGlobal('fetch', async (req: Request) => {
    if (req.method === 'POST' && new URL(req.url).pathname === '/api/v1/admin/players') posted.push(await req.clone().json())
    return server.handle(req)
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderDialog(onCreated: (res: CreatePlayerResponse) => void) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rootRoute = createRootRoute({ component: () => <CreatePlayerDialog open onClose={() => {}} onCreated={onCreated} /> })
  const router = createRouter({ routeTree: rootRoute, history: createMemoryHistory({ initialEntries: ['/admin/players'] }) })
  render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
  return router
}

describe('CreatePlayerDialog: how a bot trades (§20.8, §21.3)', { timeout: 20_000 }, () => {
  it('issues a key by default', async () => {
    const onCreated = vi.fn<(res: CreatePlayerResponse) => void>()
    const router = renderDialog(onCreated)
    const dialog = await screen.findByRole('dialog', { name: 'Add a player' }, SLOW)
    const how = within(dialog).getByRole('radiogroup', { name: 'How it trades' })
    expect((within(how).getByRole('radio', { name: 'API key (MCP or REST), shown once' }) as HTMLInputElement).checked).toBe(true)
    await userEvent.type(within(dialog).getByLabelText('Handle'), 'keybot')
    await userEvent.type(within(dialog).getByLabelText('Display name'), 'Key Bot')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create bot' }))
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1), SLOW)
    expect(posted[0]).toMatchObject({ kind: 'AI', handle: 'keybot', issueKey: true })
    expect(onCreated.mock.calls[0]![0].apiKey).not.toBeNull()
    expect(router.state.location.pathname).toBe('/admin/players')
  })

  it('creates a manual bot without a key and opens its console', async () => {
    const onCreated = vi.fn<(res: CreatePlayerResponse) => void>()
    const router = renderDialog(onCreated)
    const dialog = await screen.findByRole('dialog', { name: 'Add a player' }, SLOW)
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Manual: copy & paste prompts (no key)' }))
    expect(within(dialog).getByText('You run it from its console: copy a prompt into Gemini, Codex or any chat and paste the reply back.')).toBeTruthy()
    await userEvent.type(within(dialog).getByLabelText('Handle'), 'mb_gem')
    await userEvent.type(within(dialog).getByLabelText('Display name'), 'Gem')
    await userEvent.type(within(dialog).getByLabelText('Model label'), 'Gemini 3')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create bot' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/bots/mb_gem'), SLOW)
    expect(posted[0]).toMatchObject({ kind: 'AI', handle: 'mb_gem', aiModel: 'Gemini 3', issueKey: false })
    expect(onCreated.mock.calls[0]![0].apiKey).toBeNull()
  })
})
