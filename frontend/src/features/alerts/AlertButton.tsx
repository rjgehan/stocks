import { Suspense, lazy, useState } from 'react'
import { useAlerts } from '@/lib/api/hooks/alerts'
import { IconButton } from '@/components/ui/Button'
import { IconBell } from '@/components/ui/icons'

/** The sheet (form, list, alert logic) loads on the first press, not with the quote page. */
const AlertSheet = lazy(() => import('./AlertSheet'))

/** The quote page's bell (§22.10): opens the alert sheet; highlighted while the symbol has an active alert. */
export function AlertButton({ symbol, size = 'md' }: { symbol: string; size?: 'md' | 'touch' }) {
  const [open, setOpen] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const alerts = useAlerts()
  const active = (alerts.data ?? []).some((a) => a.symbol === symbol && a.status === 'ACTIVE')
  return (
    <>
      <IconButton
        label={active ? `${symbol} price alerts (active)` : `Set a price alert on ${symbol}`}
        size={size}
        active={active}
        onClick={() => {
          setLoaded(true)
          setOpen(true)
        }}
        data-testid="alert-button"
      >
        <IconBell />
      </IconButton>
      {loaded ? (
        <Suspense fallback={null}>
          <AlertSheet symbol={symbol} open={open} onClose={() => setOpen(false)} />
        </Suspense>
      ) : null}
    </>
  )
}
