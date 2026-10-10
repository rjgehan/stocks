import { useEffect, useState } from 'react'
import type { Bar } from '../api/types'
import { realtime } from './stomp'

/** The forming 1-minute bar from `/topic/bars.{SYM}.1Min` (§19, ≤ 1/s; a VIEWING lease), for the 1D chart. */
export function useLiveBar(symbol: string, enabled: boolean): Bar | null {
  const [bar, setBar] = useState<{ symbol: string; bar: Bar } | null>(null)
  useEffect(() => {
    if (!enabled || !symbol) return
    return realtime().subscribe(`/topic/bars.${symbol.toUpperCase()}.1Min`, (body) => setBar({ symbol, bar: body as Bar }))
  }, [symbol, enabled])
  return enabled && bar?.symbol === symbol ? bar.bar : null
}
