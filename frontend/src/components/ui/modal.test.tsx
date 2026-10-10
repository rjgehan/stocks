import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Dialog } from './Dialog'
import { Sheet } from './Sheet'

const ticketSheet = (onClose: () => void) => (
  <Sheet open onClose={onClose} title="Trade">
    <input aria-label="Shares" />
  </Sheet>
)

describe('Sheet and Dialog focus (§22.5: the ticket lives in a sheet on phones)', () => {
  it('keeps focus where the user put it when the parent re-renders with a new onClose', () => {
    const { rerender } = render(ticketSheet(() => undefined))
    const input = screen.getByLabelText('Shares')
    input.focus()
    expect(document.activeElement).toBe(input)
    // every quote tick re-renders StockScreen, which passes a fresh arrow function
    rerender(ticketSheet(() => undefined))
    rerender(ticketSheet(() => undefined))
    expect(document.activeElement).toBe(input)
  })

  it('moves focus into the panel on open and back to the opener on close', () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const { rerender } = render(<Dialog open onClose={() => undefined} title="Review">x</Dialog>)
    expect(document.activeElement?.getAttribute('role')).toBe('dialog')
    rerender(<Dialog open={false} onClose={() => undefined} title="Review">x</Dialog>)
    expect(document.activeElement).toBe(opener)
    opener.remove()
  })

  it('traps Tab and Shift+Tab inside the panel', () => {
    render(
      <Dialog open onClose={() => undefined} title="Review" footer={<button type="button">Confirm</button>}>
        <input aria-label="Price" />
      </Dialog>,
    )
    const close = screen.getByRole('button', { name: 'Close' })
    const confirm = screen.getByRole('button', { name: 'Confirm' })
    confirm.focus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(document.activeElement).toBe(close) // wrapped from the last to the first
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(confirm) // and back
  })
})

describe('Escape with stacked modals', () => {
  it('closes only the topmost modal (the review dialog over the trade sheet)', () => {
    const closeSheet = vi.fn<() => void>()
    const closeDialog = vi.fn<() => void>()
    const { rerender } = render(
      <Sheet open onClose={closeSheet} title="Trade">
        <Dialog open onClose={closeDialog} title="Review">
          x
        </Dialog>
      </Sheet>,
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(closeDialog).toHaveBeenCalledTimes(1)
    expect(closeSheet).not.toHaveBeenCalled()

    rerender(
      <Sheet open onClose={closeSheet} title="Trade">
        <Dialog open={false} onClose={closeDialog} title="Review">
          x
        </Dialog>
      </Sheet>,
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(closeSheet).toHaveBeenCalledTimes(1)
    expect(closeDialog).toHaveBeenCalledTimes(1)
  })
})
