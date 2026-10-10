import { useRouter } from '@tanstack/react-router'
import { IconChevronLeft } from '@/components/ui/icons'

/** Header for phone "pushed" screens (§22.3): back button, title, optional actions. */
export function PushedHeader({ title, subtitle, actions }: { title: React.ReactNode; subtitle?: React.ReactNode; actions?: React.ReactNode }) {
  const router = useRouter()
  return (
    <header className="sticky top-0 z-20 shrink-0 border-b border-divider bg-bg-1/95 pt-safe pl-safe pr-safe backdrop-blur">
      <div className="flex h-12 items-center gap-1 px-1">
        <button
          type="button"
          onClick={() => (window.history.length > 1 ? router.history.back() : void router.navigate({ to: '/watchlist' }))}
          aria-label="Back"
          className="inline-flex size-11 items-center justify-center rounded-md text-text-1"
        >
          <IconChevronLeft size={22} />
        </button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-section font-semibold">{title}</div>
          {subtitle ? <div className="truncate text-micro text-text-3">{subtitle}</div> : null}
        </div>
        {actions}
      </div>
    </header>
  )
}
