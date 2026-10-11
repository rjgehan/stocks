import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MockServer } from '@/lib/api/mock/server'
import { renderWithRouter } from '@/test/router'
import { ConsentScreen } from './ConsentScreen'
import { OAuthErrorScreen } from './OAuthErrorScreen'

vi.mock('./leave', () => ({ leaveTo: vi.fn<(url: string) => void>() }))
const { leaveTo } = await import('./leave')

let server: MockServer
/** The first render loads the route and the mock server; on a busy CI machine that takes longer than findBy's 1 s. */
const SLOW = { timeout: 5000 }

beforeEach(() => {
  sessionStorage.clear()
  server = new MockServer()
  vi.stubGlobal('fetch', (input: Request) => server.handle(input))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(leaveTo).mockReset()
})

describe('ConsentScreen (§17.9.3)', { timeout: 20_000 }, () => {
  it('shows the client, the redirect host and the act-as choices, then approves as the chosen player', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_screen1" />)
    expect(await screen.findByRole('heading', { name: 'Connect Claude' }, SLOW)).toBeTruthy()
    expect(screen.getByText('claude.ai', { selector: 'span.font-semibold' })).toBeTruthy()
    const bot = screen.getByRole('radio', { name: 'Claude (@claude)' }) as HTMLInputElement
    expect(bot.checked).toBe(true)
    await userEvent.click(screen.getByRole('radio', { name: 'Myself (@demo)' }))
    expect((screen.getByRole('radio', { name: 'Myself (@demo)' }) as HTMLInputElement).checked).toBe(true)
    // drop a scope, then allow
    await userEvent.click(screen.getByRole('checkbox', { name: /League/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Allow' }))
    await waitFor(() => expect(leaveTo).toHaveBeenCalledTimes(1), SLOW)
    expect(vi.mocked(leaveTo).mock.calls[0]![0]).toMatch(/^https:\/\/claude\.ai\/api\/mcp\/auth_callback\?code=/)
    expect(await screen.findByRole('heading', { name: 'Connected' }, SLOW)).toBeTruthy()
    const apps = await (await server.handle(new Request('http://localhost/api/v1/me/connected-apps'))).json()
    expect(apps[0].actAs.handle).toBe('demo')
    expect(apps[0].scopes).toEqual(['account:read', 'market:read', 'trade'])
  })

  it('preselects nothing for an unknown app and waits for a choice before Allow', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_dcr_5" />)
    expect(await screen.findByRole('heading', { name: 'Connect MCP Inspector' }, SLOW)).toBeTruthy()
    for (const radio of screen.getAllByRole('radio') as HTMLInputElement[]) expect(radio.checked).toBe(false)
    expect((screen.getByRole('button', { name: 'Allow' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('Choose who it acts as to continue.')).toBeTruthy()
    await userEvent.click(screen.getByRole('radio', { name: 'ChatGPT (@chatgpt)' }))
    expect(screen.getByRole('status').textContent).toContain('@chatgpt')
    expect((screen.getByRole('button', { name: 'Allow' }) as HTMLButtonElement).disabled).toBe(false)
  })

  it('asks for the password when the sign-in is old, then approves', async () => {
    server.expireRecentAuth()
    renderWithRouter(<ConsentScreen requestId="oar_screen6" />)
    await userEvent.click(await screen.findByRole('button', { name: 'Allow' }, SLOW))
    const dialog = await screen.findByRole('dialog', { name: "Confirm it's you" }, SLOW)
    expect(leaveTo).not.toHaveBeenCalled()
    await userEvent.type(within(dialog).getByLabelText('Password'), 'correct horse battery')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(leaveTo).toHaveBeenCalledTimes(1), SLOW)
    expect(vi.mocked(leaveTo).mock.calls[0]![0]).toMatch(/\?code=/)
  })

  it('warns about loopback redirects', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_loopback_2" />)
    expect(await screen.findByText(/runs on your own computer/, {}, SLOW)).toBeTruthy()
  })

  it('explains an expired request', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_expired_3" />)
    expect(await screen.findByRole('heading', { name: 'This request expired' }, SLOW)).toBeTruthy()
  })

  it('denies', async () => {
    renderWithRouter(<ConsentScreen requestId="oar_screen4" />)
    await userEvent.click(await screen.findByRole('button', { name: 'Deny' }, SLOW))
    await waitFor(() => expect(leaveTo).toHaveBeenCalledTimes(1), SLOW)
    expect(vi.mocked(leaveTo).mock.calls[0]![0]).toContain('error=access_denied')
    expect(await screen.findByRole('heading', { name: 'Not connected' }, SLOW)).toBeTruthy()
  })
})

describe('OAuthErrorScreen (§17.9.3)', () => {
  it('shows fixed copy per error code, never text from the URL (review finding: content spoofing)', async () => {
    renderWithRouter(<OAuthErrorScreen error="invalid_client" />)
    expect(await screen.findByText(/is not one this server accepts/, {}, SLOW)).toBeTruthy()
    expect(screen.getByText('Error code: invalid_client')).toBeTruthy()
  })

  it('falls back to a generic sentence for an unknown code, and does not echo it', async () => {
    renderWithRouter(<OAuthErrorScreen error="Your account was suspended, sign in at evil.example" />)
    expect(await screen.findByText('The connection request could not be completed.', {}, SLOW)).toBeTruthy()
    expect(screen.queryByText(/evil\.example/)).toBeNull()
  })
})
