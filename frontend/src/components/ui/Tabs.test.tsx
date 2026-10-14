import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithRouter } from '@/test/router'
import { LinkTabs } from './Tabs'

const items = [
  { to: '/players/$handle', params: { handle: 'claude' }, label: 'Overview', exact: true },
  { to: '/players/$handle/portfolio', params: { handle: 'claude' }, label: 'Portfolio' },
  { to: '/players/$handle/history', params: { handle: 'claude' }, label: 'History' },
] as const

describe('LinkTabs', () => {
  it('keeps the tab of the current path active whatever the search (?range=, ?status=)', async () => {
    renderWithRouter(<LinkTabs label="Profile sections" items={items} />, undefined, '/players/claude?range=1M')
    const overview = await screen.findByRole('link', { name: 'Overview' })
    expect(overview.getAttribute('href')).toBe('/players/claude')
    expect(overview.getAttribute('data-status')).toBe('active')
    expect(screen.getByRole('link', { name: 'Portfolio' }).getAttribute('data-status')).not.toBe('active')
  })

  it('activates a sub-route tab with its own search, and Overview only on its exact path', async () => {
    renderWithRouter(<LinkTabs label="Profile sections" items={items} />, undefined, '/players/claude/history?status=OPEN&view=orders')
    expect((await screen.findByRole('link', { name: 'History' })).getAttribute('data-status')).toBe('active')
    expect(screen.getByRole('link', { name: 'Overview' }).getAttribute('data-status')).not.toBe('active')
  })
})
