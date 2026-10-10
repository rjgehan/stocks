import { useEffect, useState } from 'react'

/** The value, settled for `ms` milliseconds (the ticket preview is debounced 250 ms, §22.5). */
export function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return settled
}
