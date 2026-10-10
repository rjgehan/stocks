import { type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes, useId } from 'react'
import { cx } from './cx'

const CONTROL =
  'w-full rounded-md border bg-bg-2 px-3 text-body text-text-1 outline-none transition-colors placeholder:text-text-3 focus:border-accent disabled:opacity-50'

interface FieldShellProps {
  label: ReactNode
  hint?: ReactNode
  error?: string | null
  className?: string
  children: (id: string, describedBy: string | undefined) => ReactNode
  labelAside?: ReactNode
}

/** Label + control + hint/error, wired for screen readers (`aria-describedby`, `aria-invalid`). */
export function FieldShell({ label, hint, error, className, children, labelAside }: FieldShellProps) {
  const id = useId()
  const describedBy = error ? `${id}-err` : hint ? `${id}-hint` : undefined
  return (
    <div className={className}>
      <div className="mb-1 flex items-baseline justify-between gap-2">
        <label htmlFor={id} className="block text-caption font-medium text-text-2">
          {label}
        </label>
        {labelAside}
      </div>
      {children(id, describedBy)}
      {error ? (
        <p id={`${id}-err`} className="mt-1 text-caption text-down">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1 text-caption text-text-3">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'prefix'> {
  label: ReactNode
  hint?: ReactNode
  error?: string | null
  prefix?: ReactNode
  labelAside?: ReactNode
}

export function TextField({ label, hint, error, className, prefix, labelAside, ...rest }: TextFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} className={className} labelAside={labelAside}>
      {(id, describedBy) =>
        prefix ? (
          <div className={cx('flex h-11 items-center rounded-md border bg-bg-2 focus-within:border-accent tablet:h-10', error ? 'border-down' : 'border-border')}>
            <span className="pl-3 text-text-3">{prefix}</span>
            <input
              id={id}
              aria-invalid={error ? true : undefined}
              aria-describedby={describedBy}
              className="h-full w-full min-w-0 bg-transparent px-2 text-body text-text-1 outline-none placeholder:text-text-3"
              {...rest}
            />
          </div>
        ) : (
          <input
            id={id}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
            className={cx(CONTROL, 'h-11 tablet:h-10', error ? 'border-down' : 'border-border')}
            {...rest}
          />
        )
      }
    </FieldShell>
  )
}

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label: ReactNode
  hint?: ReactNode
  error?: string | null
}

export function TextAreaField({ label, hint, error, className, ...rest }: TextAreaFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} className={className}>
      {(id, describedBy) => (
        <textarea
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cx(CONTROL, 'resize-none py-2', error ? 'border-down' : 'border-border')}
          {...rest}
        />
      )}
    </FieldShell>
  )
}

export interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: ReactNode
  hint?: ReactNode
  error?: string | null
  options: ReadonlyArray<{ value: string; label: string; disabled?: boolean }>
}

export function SelectField({ label, hint, error, className, options, ...rest }: SelectFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} className={className}>
      {(id, describedBy) => (
        <select
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cx(CONTROL, 'h-11 font-medium tablet:h-10', error ? 'border-down' : 'border-border')}
          {...rest}
        >
          {options.map((o) => (
            <option key={o.value} value={o.value} disabled={o.disabled}>
              {o.label}
            </option>
          ))}
        </select>
      )}
    </FieldShell>
  )
}

/** An accessible on/off switch (a checkbox with `role="switch"`), with its label and an optional description. */
export function Switch({
  checked,
  onChange,
  label,
  description,
  disabled,
  className,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: ReactNode
  description?: ReactNode
  disabled?: boolean
  className?: string
}) {
  const id = useId()
  return (
    <label htmlFor={id} className={cx('flex min-h-11 cursor-pointer items-center justify-between gap-3', disabled && 'cursor-not-allowed opacity-50', className)}>
      <span className="min-w-0">
        <span className="block text-body font-medium text-text-1">{label}</span>
        {description ? <span className="block text-caption text-text-3">{description}</span> : null}
      </span>
      <span className="relative inline-flex shrink-0 items-center">
        <input
          id={id}
          type="checkbox"
          role="switch"
          aria-checked={checked}
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="peer sr-only"
        />
        <span
          aria-hidden="true"
          className="h-6 w-10 rounded-full bg-bg-3 transition-colors peer-checked:bg-accent peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-accent"
        />
        <span aria-hidden="true" className="pointer-events-none absolute left-0.5 size-5 rounded-full bg-white shadow transition-transform peer-checked:translate-x-4" />
      </span>
    </label>
  )
}

/** A checkbox row (agreements, options). */
export function Checkbox({ checked, onChange, children, disabled, className }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode; disabled?: boolean; className?: string }) {
  return (
    <label className={cx('flex cursor-pointer items-start gap-2 text-caption text-text-1', disabled && 'cursor-not-allowed opacity-50', className)}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} className="mt-0.5 size-4 shrink-0 accent-[var(--accent)]" />
      <span>{children}</span>
    </label>
  )
}
