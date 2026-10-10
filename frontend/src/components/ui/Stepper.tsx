import { useId } from 'react'
import { IconMinus, IconPlus } from './icons'
import { cx } from './cx'

/** A labelled numeric text field with − / + buttons. Values stay strings (decimal-safe). */
export function Stepper({
  label,
  value,
  onChange,
  onStep,
  placeholder,
  error,
  prefix,
  suffix,
  inputMode = 'decimal',
  disabled,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  onStep?: (dir: 1 | -1) => void
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
      <label htmlFor={id} className="mb-1 block text-caption font-medium text-text-2">
        {label}
      </label>
      <div
        className={cx(
          'flex h-10 items-center rounded-md border bg-bg-2 transition-colors focus-within:border-accent',
          error ? 'border-down' : 'border-border',
          disabled && 'opacity-50',
        )}
      >
        {onStep ? (
          <button type="button" aria-label={`Decrease ${label}`} disabled={disabled} onClick={() => onStep(-1)} className="flex h-full w-10 items-center justify-center text-text-2 hover:text-text-1">
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
            inputMode={inputMode}
            autoComplete="off"
            placeholder={placeholder}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-err` : undefined}
            className={cx('h-full w-full min-w-0 bg-transparent px-2 text-body font-semibold text-text-1 outline-none placeholder:font-normal placeholder:text-text-3', onStep && 'text-center')}
          />
          {suffix}
        </div>
        {onStep ? (
          <button type="button" aria-label={`Increase ${label}`} disabled={disabled} onClick={() => onStep(1)} className="flex h-full w-10 items-center justify-center text-text-2 hover:text-text-1">
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
