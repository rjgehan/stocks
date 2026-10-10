import { useId } from 'react'
import { IconMinus, IconPlus } from './icons'
import { cx } from './cx'

/**
 * A labelled numeric text field with − / + buttons. Values stay strings (decimal-safe). 44 px tall on phones
 * (§22.3 touch targets), 40 px from the tablet breakpoint. `active` outlines the field the price chips will fill.
 */
export function Stepper({
  label,
  labelAside,
  value,
  onChange,
  onStep,
  onFocus,
  active,
  placeholder,
  error,
  prefix,
  suffix,
  inputMode = 'decimal',
  disabled,
}: {
  label: string
  labelAside?: React.ReactNode
  value: string
  onChange: (v: string) => void
  onStep?: (dir: 1 | -1) => void
  onFocus?: () => void
  active?: boolean
  placeholder?: string
  error?: string | null
  prefix?: string
  suffix?: React.ReactNode
  inputMode?: 'decimal' | 'numeric'
  disabled?: boolean
}) {
  const id = useId()
  return (
    <div>
      <div className="mb-1 flex min-h-5 items-center justify-between gap-2">
        <label htmlFor={id} className="block text-caption font-medium text-text-2">
          {label}
        </label>
        {labelAside}
      </div>
      <div
        className={cx(
          'flex h-11 items-center rounded-md border bg-bg-2 transition-colors focus-within:border-accent tablet:h-10',
          error ? 'border-down' : active ? 'border-accent/60' : 'border-border',
          disabled && 'opacity-50',
        )}
      >
        {onStep ? (
          <button
            type="button"
            aria-label={`Decrease ${label}`}
            disabled={disabled}
            onClick={() => onStep(-1)}
            className="flex h-full w-11 shrink-0 items-center justify-center rounded-l-md text-text-2 hover:bg-bg-3 hover:text-text-1 tablet:w-10"
          >
            <IconMinus size={16} />
          </button>
        ) : null}
        <div className="flex min-w-0 flex-1 items-center">
          {prefix ? <span className="pl-2 text-text-3">{prefix}</span> : null}
          <input
            id={id}
            value={value}
            disabled={disabled}
            onChange={(e) => onChange(e.target.value)}
            onFocus={onFocus}
            inputMode={inputMode}
            autoComplete="off"
            placeholder={placeholder}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-err` : undefined}
            className={cx(
              'h-full w-full min-w-0 bg-transparent px-2 text-body font-semibold text-text-1 tabular-nums outline-none placeholder:font-normal placeholder:text-text-3',
              onStep && 'text-center',
            )}
          />
          {suffix}
        </div>
        {onStep ? (
          <button
            type="button"
            aria-label={`Increase ${label}`}
            disabled={disabled}
            onClick={() => onStep(1)}
            className="flex h-full w-11 shrink-0 items-center justify-center rounded-r-md text-text-2 hover:bg-bg-3 hover:text-text-1 tablet:w-10"
          >
            <IconPlus size={16} />
          </button>
        ) : null}
      </div>
      {error ? (
        <p id={`${id}-err`} className="mt-1 text-caption text-down">
          {error}
        </p>
      ) : null}
    </div>
  )
}
