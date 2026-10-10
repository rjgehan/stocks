import { useToastStore, type ToastTone } from './toastStore'
import { IconClose } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'

const BAR: Record<ToastTone, string> = {
  up: 'bg-up',
  down: 'bg-down',
  neutral: 'bg-text-3',
  error: 'bg-down',
  warn: 'bg-warn',
}

/** Toasts: order accepted, fills, errors (§22.5 Result). Bottom-center on phones (above the tab bar). */
export function ToastHost() {
  const toasts = useToastStore((s) => s.toasts)
  const dismiss = useToastStore((s) => s.dismiss)
  return (
    <div
      aria-live="polite"
      className="pointer-events-none fixed inset-x-0 bottom-[calc(72px+env(safe-area-inset-bottom))] z-[60] flex flex-col items-center gap-2 px-3 tablet:inset-x-auto tablet:right-4 tablet:bottom-10 tablet:items-end"
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          role={t.tone === 'error' ? 'alert' : 'status'}
          className="pointer-events-auto flex w-full max-w-sm animate-toast-in overflow-hidden rounded-md border border-border bg-bg-1 shadow-xl"
        >
          <span className={cx('w-1 shrink-0', BAR[t.tone])} aria-hidden="true" />
          <div className="min-w-0 flex-1 px-3 py-2.5">
            <p className="text-body font-semibold text-text-1">{t.title}</p>
            {t.body ? <p className="text-caption text-text-2">{t.body}</p> : null}
          </div>
          <button type="button" onClick={() => dismiss(t.id)} aria-label="Dismiss" className="px-2 text-text-3 hover:text-text-1">
            <IconClose size={16} />
          </button>
        </div>
      ))}
    </div>
  )
}
