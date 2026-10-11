import { describe, expect, it, vi } from 'vitest'
import { act, screen } from '@testing-library/react'
import { ApiError, networkError } from '@/lib/api/problem'
import { renderWithRouter } from '@/test/router'
import { OfflineNotice, isOfflineError } from './OfflineNotice'

describe('the offline shell page (§22.10)', () => {
  it('recognizes an unreachable server, not other failures', () => {
    expect(isOfflineError(networkError(new TypeError('Failed to fetch')))).toBe(true)
    const serverError = new ApiError({
      type: null,
      title: 'Internal error',
      status: 500,
      code: 'INTERNAL_ERROR',
      detail: null,
      hint: null,
      fields: [],
      retryAfterS: null,
      requestId: null,
    })
    expect(isOfflineError(serverError)).toBe(false)
    expect(isOfflineError(new Error('boom'))).toBe(false)
  })

  it('says the app is offline and retries when the browser is back online', async () => {
    const reset = vi.fn<() => void>()
    renderWithRouter(<OfflineNotice reset={reset} />)
    expect(await screen.findByText('You appear to be offline')).toBeTruthy()
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    expect(reset).toHaveBeenCalledTimes(1)
    screen.getByRole('button', { name: 'Try again' }).click()
    expect(reset).toHaveBeenCalledTimes(2)
  })
})
