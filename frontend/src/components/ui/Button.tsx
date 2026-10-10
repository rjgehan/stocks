import type { ButtonHTMLAttributes } from 'react'
import { cx } from './cx'

export type ButtonVariant = 'primary' | 'buy' | 'sell' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md' | 'lg'

const VARIANTS: Record<ButtonVariant, string> = {
  // §22.8: Buy uses --up, Sell --down, other primary actions --accent. Text on up/down fills is --on-updown
  // (white fails 4.5:1 there); hover brightens the fill, since the -strong shades would lower the contrast.
  primary: 'bg-accent text-white hover:bg-accent-hover',
  buy: 'bg-up text-on-updown hover:brightness-110',
  sell: 'bg-down text-on-updown hover:brightness-110',
  secondary: 'bg-bg-2 text-text-1 hover:bg-bg-3 border border-border',
  ghost: 'bg-transparent text-text-2 hover:bg-bg-2 hover:text-text-1',
  danger: 'bg-transparent text-down border border-down/40 hover:bg-down-bg',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-caption',
  md: 'h-10 px-4 text-body',
  lg: 'h-12 px-5 text-section',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  block?: boolean
}

export function Button({ variant = 'secondary', size = 'md', block, className, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={cx(
        'inline-flex select-none items-center justify-center gap-2 rounded-md font-semibold transition-colors duration-150',
        'disabled:opacity-45 disabled:hover:bg-[inherit]',
        VARIANTS[variant],
        SIZES[size],
        block && 'w-full',
        className,
      )}
      {...rest}
    />
  )
}

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  label: string
  size?: 'sm' | 'md' | 'touch'
  active?: boolean
}

/** Square icon button; `touch` is the 44 px phone target (§22.3). */
export function IconButton({ label, size = 'md', active, className, type = 'button', children, ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      className={cx(
        'inline-flex shrink-0 items-center justify-center rounded-md transition-colors duration-150',
        size === 'sm' ? 'size-8' : size === 'md' ? 'size-9' : 'size-11',
        active ? 'bg-accent-bg text-accent' : 'text-text-2 hover:bg-bg-2 hover:text-text-1',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  )
}
