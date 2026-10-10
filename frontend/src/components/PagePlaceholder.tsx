import { Pill } from '@/components/ui/Pill'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'

/**
 * Phase 0 placeholder: the right layout skeleton for a §22 screen, with a note of what lands and when.
 * Replace with the real screen in the milestone named by `milestone`.
 */
export function PagePlaceholder({
  title,
  milestone,
  description,
  children,
  layout = 'table',
  className,
}: {
  title: string
  milestone: string
  description: string
  children?: React.ReactNode
  layout?: 'table' | 'cards' | 'form' | 'none'
  className?: string
}) {
  return (
    <div className={cx('mx-auto w-full max-w-6xl space-y-4 px-4 py-4 desktop:px-6', className)}>
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-[20px] leading-7 font-bold tracking-tight">{title}</h1>
        <Pill tone="neutral">{milestone}</Pill>
      </div>
      <p className="max-w-2xl text-caption text-text-2">{description}</p>
      {children}
      {layout === 'table' ? (
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={6} cols={5} />
        </div>
      ) : null}
      {layout === 'cards' ? (
        <div className="grid gap-3 tablet:grid-cols-2 desktop:grid-cols-3">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="space-y-2 rounded-lg border border-divider bg-bg-1 p-4">
              <Skeleton className="h-4 w-1/3" />
              <Skeleton className="h-7 w-1/2" />
              <Skeleton className="h-3 w-2/3" />
            </div>
          ))}
        </div>
      ) : null}
      {layout === 'form' ? (
        <div className="max-w-xl space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="space-y-1.5">
              <Skeleton className="h-3 w-28" />
              <Skeleton className="h-10 w-full" />
            </div>
          ))}
        </div>
      ) : null}
    </div>
  )
}
