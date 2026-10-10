import type { HTMLAttributes } from 'react'
import { cx } from './cx'

export type PillTone = 'up' | 'down' | 'flat' | 'accent' | 'warn' | 'ai' | 'neutral' | 'solid-up' | 'solid-down' | 'solid-flat'

const TONES: Record<PillTone, string> = {
  up: 'bg-up-bg text-up',
  down: 'bg-down-bg text-down',
  flat: 'bg-bg-2 text-flat',
  accent: 'bg-accent-bg text-accent',
  warn: 'bg-warn/15 text-warn',
  ai: 'bg-ai-bg text-ai',
  neutral: 'bg-bg-2 text-text-2',
  'solid-up': 'bg-up text-white',
  'solid-down': 'bg-down text-white',
  'solid-flat': 'bg-bg-3 text-text-1',
}

export function Pill({ tone = 'neutral', className, ...rest }: HTMLAttributes<HTMLSpanElement> & { tone?: PillTone }) {
  return (
    <span
      className={cx(
        'inline-flex h-5 shrink-0 items-center gap-1 rounded-sm px-1.5 text-micro font-semibold uppercase tracking-wide',
        TONES[tone],
        className,
      )}
      {...rest}
    />
  )
}
