import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { describeError } from '@/lib/api/errors'
import { useLeagueSettings, useUpdateLeagueSettings } from '@/lib/api/hooks/admin'
import { isApiError } from '@/lib/api/problem'
import type { Settings, UpdateSettingsRequest } from '@/lib/api/types'
import { isDecimal, lt } from '@/lib/format/decimal'
import { Button } from '@/components/ui/Button'
import { SelectField, Switch, TextField } from '@/components/ui/Field'
import { Skeleton } from '@/components/ui/Skeleton'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/admin/settings')({
  component: SettingsPage,
})

const DELAYS = [
  { value: 'PT0S', label: 'No delay: public (default)' },
  { value: 'PT5M', label: '5 minutes' },
  { value: 'PT15M', label: '15 minutes' },
  { value: 'PT1H', label: '1 hour' },
  { value: 'P1D', label: '1 day' },
]

/**
 * Admin · League settings (§21.3, §23.4): starting cash, self-reset, season start, eligibility, feed delay, rationale,
 * 24/7 crypto trading, long options.
 * Holdings are always public (public mode, §16.7), so there is no visibility setting any more.
 */
function SettingsPage() {
  const settings = useLeagueSettings()
  if (settings.isPending) return <Skeleton className="h-96 w-full max-w-2xl" />
  if (!settings.data) return <p className="text-caption text-text-3">League settings are unavailable right now.</p>
  return <SettingsForm initial={settings.data} />
}

function SettingsForm({ initial }: { initial: Settings }) {
  const update = useUpdateLeagueSettings()
  const [s, setS] = useState<Settings>(initial)
  const set = <K extends keyof Settings>(k: K, v: Settings[K]) => setS((prev) => ({ ...prev, [k]: v }))
  const changed = (Object.keys(s) as Array<keyof Settings>).filter((k) => s[k] !== initial[k])
  const cash = s['league.defaultStartingCash'] ?? ''
  const cashError = !isDecimal(cash) || lt(cash, '1.00') ? 'At least $1.00.' : null
  const server = update.error && isApiError(update.error) ? update.error : null
  const delayOptions = DELAYS.some((d) => d.value === s['social.tradeFeedDelay']) ? DELAYS : [...DELAYS, { value: s['social.tradeFeedDelay'] ?? '', label: s['social.tradeFeedDelay'] ?? '' }]

  const save = () => {
    const patch: UpdateSettingsRequest = {}
    for (const k of changed) (patch as Record<string, unknown>)[k] = s[k]
    update.mutate(patch, {
      onSuccess: (saved) => {
        setS(saved)
        toast({ tone: 'neutral', title: 'League settings saved' })
      },
    })
  }

  return (
    <form
      className="max-w-2xl space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save()
      }}
    >
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">Money</h2>
        <TextField
          label="Default starting cash"
          prefix="$"
          inputMode="decimal"
          value={cash}
          onChange={(e) => set('league.defaultStartingCash', e.target.value.replace(/[^\d.]/g, ''))}
          error={cashError ?? server?.fieldErrors['league.defaultStartingCash']}
          hint="New players and resets start with this. Existing accounts are not changed (use Money to deposit)."
        />
        <Switch
          checked={s['league.allowSelfReset']}
          onChange={(v) => set('league.allowSelfReset', v)}
          label="Allow self-reset"
          description="Players can restart their own account from Settings. Resets never erase a loss from the board."
        />
      </section>
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">League</h2>
        <TextField label="Season start" type="date" value={s['league.seasonStart'] ?? ''} onChange={(e) => set('league.seasonStart', e.target.value)} hint="The SEASON period counts from this date's previous close." />
        <div className="grid grid-cols-2 gap-3">
          <TextField
            label="Min fills to rank"
            type="number"
            min={0}
            value={String(s['league.eligibility.minFills'])}
            onChange={(e) => set('league.eligibility.minFills', Math.max(0, Math.trunc(Number(e.target.value) || 0)))}
          />
          <TextField
            label="Min symbols to rank"
            type="number"
            min={0}
            value={String(s['league.eligibility.minSymbols'])}
            onChange={(e) => set('league.eligibility.minSymbols', Math.max(0, Math.trunc(Number(e.target.value) || 0)))}
          />
        </div>
        <Switch
          checked={s['league.humanKindExcludesApiTraders']}
          onChange={(v) => set('league.humanKindExcludesApiTraders', v)}
          label="Humans who trade via API count as AI"
          description="With this on, a human with an API-key fill in the window is listed under AI, not Humans."
        />
      </section>
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">Social</h2>
        <SelectField
          label="Trade feed delay"
          value={s['social.tradeFeedDelay'] ?? 'PT0S'}
          onChange={(e) => set('social.tradeFeedDelay', e.target.value)}
          options={delayOptions}
          hint="Holds back only the feed, trade lists and journal notes. Portfolios, orders, activity and P&L are always live."
        />
        <Switch checked={s['social.showDepositsInFeed']} onChange={(v) => set('social.showDepositsInFeed', v)} label="Show deposits in the feed" />
        <Switch checked={s['bots.requireRationale']} onChange={(v) => set('bots.requireRationale', v)} label="Bots must explain each order" description="AI orders without a rationale are rejected (RATIONALE_REQUIRED)." />
      </section>
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">Crypto</h2>
        <Switch
          checked={s['crypto.enabled'] ?? true}
          onChange={(v) => set('crypto.enabled', v)}
          label="Crypto trading (24/7)"
          description="BTC/USD, ETH/USD and 10 more pairs trade around the clock. Off: crypto buys are refused (sells still work) and weekends stop being P&L days."
        />
      </section>
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">Options</h2>
        <Switch
          checked={s['options.enabled'] ?? true}
          onChange={(v) => set('options.enabled', v)}
          label="Options trading"
          description="Long calls and puts on stocks and ETFs (buy to open, sell to close, cash-settled at expiration). Off: option buys are refused; holders can still sell to close."
        />
      </section>
      {update.error ? (
        <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
          {describeError(update.error).title}
          {server?.problem.detail ? <span className="block text-text-2">{server.problem.detail}</span> : null}
        </p>
      ) : null}
      <div className="flex gap-2">
        <Button type="submit" variant="primary" disabled={changed.length === 0 || Boolean(cashError) || update.isPending}>
          {update.isPending ? 'Saving…' : changed.length ? `Save ${changed.length} change${changed.length > 1 ? 's' : ''}` : 'Saved'}
        </Button>
        {changed.length ? (
          <Button variant="ghost" onClick={() => setS(initial)}>
            Discard
          </Button>
        ) : null}
      </div>
    </form>
  )
}
