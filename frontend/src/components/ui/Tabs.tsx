import { Link, type LinkProps } from '@tanstack/react-router'
import { cx } from './cx'

export interface TabItem<T extends string> {
  value: T
  label: React.ReactNode
}

/** Underline tabs driven by local state (or a search param the caller owns). */
export function Tabs<T extends string>({
  value,
  items,
  onChange,
  label,
  className,
}: {
  value: T
  items: ReadonlyArray<TabItem<T>>
  onChange: (v: T) => void
  label: string
  className?: string
}) {
  return (
    <div role="tablist" aria-label={label} className={cx('scrollbar-none flex gap-5 overflow-x-auto border-b border-divider', className)}>
      {items.map((t) => (
        <button
          key={t.value}
          role="tab"
          type="button"
          aria-selected={t.value === value}
          onClick={() => onChange(t.value)}
          className={cx(
            '-mb-px h-10 shrink-0 border-b-2 text-body font-semibold transition-colors',
            t.value === value ? 'border-accent text-text-1' : 'border-transparent text-text-2 hover:text-text-1',
          )}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

/** Underline tabs that are router links (sub-routes such as /account/orders). */
export function LinkTabs({ items, className, label }: { items: ReadonlyArray<{ to: LinkProps['to']; label: string; exact?: boolean }>; className?: string; label: string }) {
  return (
    <nav aria-label={label} className={cx('scrollbar-none flex gap-5 overflow-x-auto border-b border-divider', className)}>
      {items.map((t) => (
        <Link
          key={t.label}
          to={t.to}
          activeOptions={{ exact: t.exact ?? false }}
          className="-mb-px flex h-10 shrink-0 items-center border-b-2 border-transparent text-body font-semibold text-text-2 transition-colors hover:text-text-1 data-[status=active]:border-accent data-[status=active]:text-text-1"
        >
          {t.label}
        </Link>
      ))}
    </nav>
  )
}
