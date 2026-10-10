import { cx } from './cx'

export interface SegmentOption<T extends string> {
  value: T
  label: React.ReactNode
  disabled?: boolean
  /** Selected-state tone; Buy/Sell use up/down (§22.5). */
  tone?: 'accent' | 'up' | 'down'
  title?: string
}

export interface SegmentedProps<T extends string> {
  value: T
  options: ReadonlyArray<SegmentOption<T>>
  onChange: (value: T) => void
  label: string
  size?: 'sm' | 'md'
  block?: boolean
}

const TONES = {
  accent: 'bg-bg-1 text-text-1 shadow-sm',
  up: 'bg-up text-on-updown',
  down: 'bg-down text-on-updown',
} as const

export function Segmented<T extends string>({ value, options, onChange, label, size = 'md', block }: SegmentedProps<T>) {
  return (
    <div role="radiogroup" aria-label={label} className={cx('inline-flex rounded-md bg-bg-2 p-0.5', block && 'flex w-full')}>
      {options.map((o) => {
        const selected = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={o.disabled}
            title={o.title}
            onClick={() => onChange(o.value)}
            className={cx(
              'flex-1 whitespace-nowrap rounded-[6px] font-semibold transition-colors duration-150 disabled:opacity-40',
              size === 'sm' ? 'h-7 px-2.5 text-caption' : 'h-9 px-3 text-body',
              selected ? TONES[o.tone ?? 'accent'] : 'text-text-2 hover:text-text-1',
            )}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
