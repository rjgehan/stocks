import { type ReactNode, useState } from 'react'
import { Button, type ButtonVariant } from './Button'
import { Dialog } from './Dialog'

/**
 * A confirmation dialog. With `typeToConfirm`, Confirm stays disabled until the user types that exact text
 * (§21.3: transfers require typing the amount; self-reset requires "RESET").
 */
export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  children,
  confirmLabel = 'Confirm',
  tone = 'primary',
  pending,
  typeToConfirm,
  error,
}: {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: ReactNode
  children?: ReactNode
  confirmLabel?: string
  tone?: ButtonVariant
  pending?: boolean
  typeToConfirm?: string
  error?: ReactNode
}) {
  const [typed, setTyped] = useState('')
  const ready = !typeToConfirm || typed.trim() === typeToConfirm
  const close = () => {
    setTyped('')
    onClose()
  }
  return (
    <Dialog
      open={open}
      onClose={close}
      title={title}
      footer={
        <div className="flex gap-2">
          <Button variant="secondary" size="lg" className="flex-1" onClick={close}>
            Cancel
          </Button>
          <Button variant={tone} size="lg" className="flex-[1.4]" disabled={!ready || pending} onClick={onConfirm}>
            {pending ? 'Working…' : confirmLabel}
          </Button>
        </div>
      }
    >
      <div className="space-y-3 p-4 text-body text-text-2">
        {children}
        {typeToConfirm ? (
          <label className="block">
            <span className="mb-1 block text-caption font-medium text-text-2">
              Type <span className="font-mono font-semibold text-text-1">{typeToConfirm}</span> to confirm
            </span>
            <input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoComplete="off"
              spellCheck={false}
              aria-label={`Type ${typeToConfirm} to confirm`}
              className="h-11 w-full rounded-md border border-border bg-bg-2 px-3 font-mono text-body text-text-1 outline-none focus:border-accent"
            />
          </label>
        ) : null}
        {error ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {error}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}
