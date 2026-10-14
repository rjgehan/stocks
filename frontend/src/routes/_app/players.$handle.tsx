import { Link, Outlet, createFileRoute } from '@tanstack/react-router'
import { usePlayerLiveRefresh, usePlayerProfile } from '@/lib/api/hooks/league'
import { isApiError } from '@/lib/api/problem'
import { formatMoney } from '@/lib/format/money'
import { formatPct, pctDirection } from '@/lib/format/number'
import { formatDateEt } from '@/lib/format/time'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { EmptyState } from '@/components/ui/EmptyState'
import { Skeleton } from '@/components/ui/Skeleton'
import { LinkTabs } from '@/components/ui/Tabs'
import { cx } from '@/components/ui/cx'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { DIRECTION_TEXT } from '@/features/market/Change'

export const Route = createFileRoute('/_app/players/$handle')({
  staticData: { chrome: 'full', phone: 'pushed' },
  component: PlayerLayout,
})

/**
 * Player profile (§22.4, public mode §16.7): the header (who, live equity, net contributions and deposits), then
 * Overview · Portfolio · History · Analysis. Everything is public and live; a `/topic/feed` item of this player
 * refreshes the open tab (`usePlayerLiveRefresh`).
 */
function PlayerLayout() {
  const { handle } = Route.useParams()
  const bp = useBreakpoint()
  const profile = usePlayerProfile(handle)
  usePlayerLiveRefresh(handle)
  const p = profile.data
  const player = p?.player
  const header = bp === 'phone' ? <PushedHeader title={player?.displayName ?? `@${handle}`} subtitle={`@${handle}`} /> : null

  if (profile.isError) {
    return (
      <div>
        {header}
        <EmptyState
          title={isApiError(profile.error) && profile.error.code === 'PLAYER_NOT_FOUND' ? `No player @${handle}` : 'This profile is unavailable right now'}
          action={
            <Link to="/league" className="text-accent">
              Back to the league
            </Link>
          }
        />
      </div>
    )
  }

  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      {header}
      <div className="mx-auto w-full max-w-5xl space-y-4 px-4 py-4 desktop:px-6">
        <section className="flex flex-wrap items-center gap-4 rounded-lg border border-divider bg-bg-1 p-4">
          <Avatar player={player} size={56} />
          {/* a real basis: at 390 px the stats wrap below instead of squeezing "@bob · member since …" to one word a line */}
          <div className="min-w-0 flex-1 basis-[14rem]">
            {p ? (
              <>
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-[22px] leading-7 font-bold tracking-tight">{player?.displayName}</h1>
                  {player?.kind === 'AI' ? <AiChip model={player.aiModel} /> : null}
                </div>
                <p className="text-caption text-text-2">
                  @{player?.handle} · member since {formatDateEt(player?.memberSince?.slice(0, 10))}
                  {player?.resets ? ` · ${player.resets} reset${player.resets > 1 ? 's' : ''}` : ''}
                  {p.ownerHandle ? (
                    <>
                      {' '}
                      · run by{' '}
                      <Link to="/players/$handle" params={{ handle: p.ownerHandle }} className="text-accent">
                        @{p.ownerHandle}
                      </Link>
                    </>
                  ) : null}
                </p>
              </>
            ) : (
              <Skeleton className="h-12 w-48" />
            )}
          </div>
          {p ? (
            <dl className="grid w-full grid-cols-2 gap-4 border-t border-divider pt-3 tablet:flex tablet:w-auto tablet:gap-6 tablet:border-0 tablet:pt-0 tablet:text-right">
              <div>
                <dt className="text-micro text-text-3 uppercase">Equity</dt>
                <dd className="text-section font-semibold">{p.equity ? formatMoney(p.equity) : <span className="text-text-3">—</span>}</dd>
              </div>
              <div>
                <dt className="text-micro text-text-3 uppercase">Net contributions</dt>
                <dd className="text-section font-semibold">{formatMoney(p.netContributions)}</dd>
                {p.deposits?.count ? (
                  <dd className="text-micro text-text-3">
                    {p.deposits.count} deposit{p.deposits.count > 1 ? 's' : ''} · {formatMoney(p.deposits.total)}
                  </dd>
                ) : null}
              </div>
            </dl>
          ) : null}
          {p ? (
            <p className="w-full border-t border-divider pt-2 text-caption text-text-2">
              <span className="font-semibold text-text-1">{p.fills ?? 0}</span> fills
              {p.winRate ? (
                <>
                  {' · '}win rate <span className="font-semibold text-text-1">{formatPct(p.winRate, { signed: false, scale: 0 })}</span>
                </>
              ) : null}
              {p.moneyWeightedReturnAll ? (
                <>
                  {' · '}money-weighted return{' '}
                  <span className={cx('font-semibold', DIRECTION_TEXT[pctDirection(p.moneyWeightedReturnAll)])}>{formatPct(p.moneyWeightedReturnAll)}</span>
                </>
              ) : null}
              {p.runNo && p.runNo > 1 ? ` · run #${p.runNo}` : ''}
            </p>
          ) : null}
        </section>

        <LinkTabs
          label="Profile sections"
          items={[
            { to: '/players/$handle', params: { handle }, label: 'Overview', exact: true },
            { to: '/players/$handle/portfolio', params: { handle }, label: 'Portfolio' },
            { to: '/players/$handle/history', params: { handle }, label: 'History' },
            { to: '/players/$handle/analysis', params: { handle }, label: 'Analysis' },
          ]}
        />
        <Outlet />
      </div>
    </div>
  )
}
