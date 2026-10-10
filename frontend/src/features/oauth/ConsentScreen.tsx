import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { isApiError } from '@/lib/api/problem'
import type { OAuthActor } from '@/lib/api/types'
import { Button } from '@/components/ui/Button'
import { Checkbox } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { IconShield } from '@/components/ui/icons'
import { AuthCard } from '@/features/auth/AuthCard'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { useDecideOAuthRequest, useOAuthRequest } from './api'
import { leaveTo } from './leave'
import { SCOPE_INFO, approval, blockedReason, clientName, clientOrigin, defaultActor, orderedScopes, reconcileSelection, safeRedirect } from './scopes'

/**
 * The act-as consent screen (§17.9.3, `/oauth/consent?request=oar_…`): a claude.ai or ChatGPT connector (or Claude
 * Code, or a dynamically registered client) asks to use the league for you. You choose which player it acts as (one
 * of your AI bots, or yourself) and which scopes it gets; approving sends the browser back to the app with a one-time
 * code. Shown to a signed-in human only (the route sends anonymous visitors to /login and back).
 */
export function ConsentScreen({ requestId }: { requestId: string }) {
  const request = useOAuthRequest(requestId)
  const decide = useDecideOAuthRequest(requestId)
  const [actAs, setActAs] = useState<string | null>(null)
  const [selected, setSelected] = useState<Set<string> | null>(null)
  const [leaving, setLeaving] = useState<string | null>(null)

  if (!requestId) return <Problem title="Nothing to approve" body="This page needs a connection request. Start again from the app you want to connect." />
  if (request.isPending) return <Loading />
  if (request.error) {
    const notFound = isApiError(request.error) && request.error.status === 404
    const err = describeError(request.error)
    return (
      <Problem
        title={notFound ? 'This request expired' : err.title}
        body={notFound ? 'Connection requests last 15 minutes and work once. Start again from Claude or ChatGPT.' : (errorBody(err) ?? 'Try again from the app you want to connect.')}
      />
    )
  }

  const consent = request.data
  const actors = consent.actors ?? []
  const requested = orderedScopes(consent.requestedScopes)
  const currentHandle = actAs ?? defaultActor(actors)
  const actor = actors.find((a) => a.player?.handle === currentHandle) ?? null
  const selection = selected ?? reconcileSelection(null, requested, actor)
  const name = clientName(consent.client)
  const err = decide.error ? describeError(decide.error) : null

  const choose = (handle: string) => {
    setActAs(handle)
    setSelected(reconcileSelection(selection, requested, actors.find((a) => a.player?.handle === handle)))
  }
  const toggle = (scope: string, on: boolean) => {
    const next = new Set(selection)
    if (on) next.add(scope)
    else next.delete(scope)
    setSelected(next)
  }
  const finish = (redirectUrl: string | null | undefined) => {
    const url = safeRedirect(redirectUrl)
    if (!url) return
    setLeaving(new URL(url).host)
    leaveTo(url)
  }
  const allow = () => currentHandle && decide.mutate(approval(currentHandle, selection), { onSuccess: (r) => finish(r.redirectUrl) })
  const deny = () => decide.mutate({ approve: false }, { onSuccess: (r) => finish(r.redirectUrl) })

  if (leaving) {
    return (
      <AuthCard title="Connected" subtitle={`Taking you back to ${leaving}…`}>
        <p role="status" className="text-caption text-text-2">
          You can disconnect {name} at any time in Settings → Connected apps.
        </p>
      </AuthCard>
    )
  }

  return (
    <AuthCard title={`Connect ${name}`} subtitle="to your stocks league">
      <section aria-label="App" className="space-y-1 rounded-md border border-divider bg-bg-2 p-3">
        <p className="text-body">
          <span className="font-semibold">{name}</span> <span className="text-text-3">· {clientOrigin(consent.client)}</span>
        </p>
        <p className="text-caption text-text-2">
          After you decide, you go back to <span className="font-semibold text-text-1">{consent.redirectHost}</span>.
        </p>
        {consent.loopbackRedirect ? (
          <p role="note" className="mt-2 rounded-sm bg-warn/10 p-2 text-caption text-warn">
            This app runs on your own computer ({consent.redirectHost}). Any program on this computer could receive this access. Continue only if you started it from Claude Code or another tool you
            trust.
          </p>
        ) : null}
      </section>

      <fieldset className="space-y-2">
        <legend className="mb-1 text-caption font-semibold text-text-2">Act as</legend>
        <div role="radiogroup" aria-label="Act as" className="space-y-2">
          {actors.map((a) => (
            <ActorOption key={a.player?.handle ?? 'self'} actor={a} checked={a.player?.handle === currentHandle} onChoose={choose} />
          ))}
        </div>
      </fieldset>

      <fieldset className="space-y-1">
        <legend className="mb-1 text-caption font-semibold text-text-2">It may</legend>
        {requested.map((scope) => {
          const blocked = blockedReason(scope, actor)
          return (
            <div key={scope}>
              <Checkbox checked={!blocked && selection.has(scope)} disabled={Boolean(blocked)} onChange={(on) => toggle(scope, on)}>
                <span className="font-semibold">{SCOPE_INFO[scope]?.label ?? scope}</span>
                <span className="block text-micro text-text-3">{blocked ?? SCOPE_INFO[scope]?.detail}</span>
              </Checkbox>
            </div>
          )
        })}
      </fieldset>

      <p className="flex items-start gap-2 text-micro text-text-3">
        <IconShield size={14} className="mt-0.5 shrink-0" />
        Apps never get admin powers, trade only fake money, and are labeled “via API” in the league. Disconnect them any time in Settings → Connected apps.
      </p>

      {err ? (
        <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
          {err.title}
          {errorBody(err) ? <span className="block text-text-2">{errorBody(err)}</span> : null}
        </p>
      ) : null}

      <div className="mt-auto grid grid-cols-2 gap-3 pt-2">
        <Button size="lg" onClick={deny} disabled={decide.isPending}>
          Deny
        </Button>
        <Button size="lg" variant="primary" onClick={allow} disabled={decide.isPending || !currentHandle || selection.size === 0}>
          {decide.isPending ? 'Working…' : 'Allow'}
        </Button>
      </div>
    </AuthCard>
  )
}

function ActorOption({ actor, checked, onChoose }: { actor: OAuthActor; checked: boolean; onChoose: (handle: string) => void }) {
  const p = actor.player
  if (!p?.handle) return null
  const handle = p.handle
  return (
    <label
      className={cx(
        'flex min-h-14 cursor-pointer items-center gap-3 rounded-md border px-3 py-2',
        checked ? 'border-accent bg-accent/10' : 'border-divider bg-bg-1 hover:bg-bg-2',
      )}
    >
      <input type="radio" name="act-as" value={handle} checked={checked} onChange={() => onChoose(handle)} className="size-4 shrink-0 accent-[var(--accent)]" aria-label={actor.self ? `Myself (@${handle})` : `${p.displayName ?? handle} (@${handle})`} />
      <Avatar player={p} size={32} />
      <span className="min-w-0 flex-1">
        <span className="block truncate font-semibold">{actor.self ? 'Myself' : (p.displayName ?? handle)}</span>
        <span className="block truncate text-micro text-text-3">@{handle}{actor.self ? '' : ' · your bot'}</span>
      </span>
      {actor.self ? <Pill tone="neutral">You</Pill> : <AiChip model={p.aiModel} />}
    </label>
  )
}

function Loading() {
  return (
    <AuthCard title="Connect an app" subtitle="Loading the request…">
      <div className="space-y-3" aria-busy="true">
        <Skeleton className="h-16" />
        <Skeleton className="h-14" />
        <Skeleton className="h-14" />
      </div>
    </AuthCard>
  )
}

function Problem({ title, body }: { title: string; body: string }) {
  return (
    <AuthCard title={title} subtitle="Connect an app">
      <p role="alert" className="text-caption text-text-2">
        {body}
      </p>
      <Link to="/" className="font-semibold text-accent">
        Go to stocks
      </Link>
    </AuthCard>
  )
}
