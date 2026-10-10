import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MockServer } from '@/lib/api/mock/server'
import { renderWithRouter } from '@/test/router'
import { ConsentScreen } from './ConsentScreen'

vi.mock('./leave', () => ({ leaveTo: vi.fn<(url: string) => void>() }))
const { leaveTo } = await import('./leave')

let server: MockServer

beforeEach(() => {
  sessionStorage.clear()
  server = new MockServer()
  vi.stubGlobal('fetch', (input: Request) => server.handle(input))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(leaveTo).mockReset()
})

describe('ConsentScreen (§17.9.3)', () => {
  it('shows the client, the redirect host and the act-as choices, then approves as the chosen player', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_screen1" />)
    expect(await screen.findByRole('heading', { name: 'Connect Claude' })).toBeTruthy()
    expect(screen.getByText('claude.ai', { selector: 'span.font-semibold' })).toBeTruthy()
    const bot = screen.getByRole('radio', { name: 'Claude (@claude)' }) as HTMLInputElement
    expect(bot.checked).toBe(true)
    await userEvent.click(screen.getByRole('radio', { name: 'Myself (@demo)' }))
    expect((screen.getByRole('radio', { name: 'Myself (@demo)' }) as HTMLInputElement).checked).toBe(true)
    // drop a scope, then allow
    await userEvent.click(screen.getByRole('checkbox', { name: /League/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Allow' }))
    await waitFor(() => expect(leaveTo).toHaveBeenCalledTimes(1))
    expect(vi.mocked(leaveTo).mock.calls[0]![0]).toMatch(/^https:\/\/claude\.ai\/api\/mcp\/auth_callback\?code=/)
    const apps = await (await server.handle(new Request('http://localhost/api/v1/me/connected-apps'))).json()
    expect(apps[0].actAs.handle).toBe('demo')
    expect(apps[0].scopes).toEqual(['account:read', 'market:read', 'trade'])
  })

  it('warns about loopback redirects', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_loopback_2" />)
    expect(await screen.findByText(/runs on your own computer/)).toBeTruthy()
  })

  it('explains an expired request', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_expired_3" />)
    expect(await screen.findByRole('heading', { name: 'This request expired' })).toBeTruthy()
  })

  it('denies', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_screen4" />)
    await userEvent.click(await screen.findByRole('button', { name: 'Deny' }))
    await waitFor(() => expect(leaveTo).toHaveBeenCalledTimes(1))
    expect(vi.mocked(leaveTo).mock.calls[0]![0]).toContain('error=access_denied')
  })
})
