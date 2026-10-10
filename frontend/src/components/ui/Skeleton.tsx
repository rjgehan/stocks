import { cx } from './cx'

/** A loading placeholder. A block-level <span>, so it is valid inside text containers such as <p>. */
export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden="true" className={cx('block animate-pulse rounded-sm bg-bg-2', className)} />
}

/** A table-shaped skeleton for placeholder screens. */
export function SkeletonRows({ rows = 6, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="divide-y divide-divider" aria-hidden="true">
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="flex items-center gap-4 py-3">
          {Array.from({ length: cols }, (_unused, c) => (
            <Skeleton key={c} className={cx('h-3.5', c === 0 ? 'w-24' : 'flex-1')} />
          ))}
        </div>
      ))}
    </div>
  )
}
