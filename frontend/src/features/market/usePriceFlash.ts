import { useState } from 'react'
import { compare, isDecimal } from '@/lib/format/decimal'
import type { Direction } from '@/lib/format/number'

interface FlashState {
  price: string | null | undefined
  dir: Direction
  key: number
}

/**
 * 400 ms price flash (§22.4, §22.8): returns the direction of the last change and a key that changes with
 * every new price, so the CSS animation restarts. Disabled automatically under prefers-reduced-motion.
 * (Derived state from the previous render, the pattern React recommends over refs or effects.)
 */
export function usePriceFlash(price: string | null | undefined): { dir: Direction; key: number } {
  const [state, setState] = useState<FlashState>({ price, dir: 'flat', key: 0 })
  if (price !== state.price) {
    let dir: Direction = 'flat'
    if (price != null && state.price != null && isDecimal(price) && isDecimal(state.price)) {
      const c = compare(price, state.price)
      dir = c > 0 ? 'up' : c < 0 ? 'down' : 'flat'
    }
    const next = { price, dir, key: state.key + 1 }
    setState(next)
    return next
  }
  return state
}

const FLASH_CLASS: Record<Direction, string> = { up: 'animate-flash-up', down: 'animate-flash-down', flat: '' }

/** The flash as a class name plus a remount key (NAV "rolls on change", §22.8). */
export function useRollingFlash(value: string | null | undefined): { key: number; className: string } {
  const f = usePriceFlash(value)
  return { key: f.key, className: FLASH_CLASS[f.dir] }
}
