import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { api, unwrap } from '@/lib/api/client'
import { describeError, errorBody } from '@/lib/api/errors'
import { manualKeys, useCheckManualReply, useCreateManualSession, useExecuteManualSession, useManualSession, useMyManualBots, usePlayerManualSessions } from '@/lib/api/hooks/manualBots'
import { isApiError } from '@/lib/api/problem'
import type { ManualAction, ManualBot, ManualSession } from '@/lib/api/types'
import { etDatePlusDays, formatTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill, type PillTone } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { IconCheck, IconCopy, IconRobot } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { toast } from '@/features/notifications/toastStore'
import { combinePrompts, splitReplies } from './batchPrompts'
import { hasError, isDone, isTickable, issueLine, resultLabel, resultTone, rowOrder, rowSize, rowTif, sortIssues } from './manualView'

const ACTION_TONE: Record<string, PillTone> = { BUY: 'up', SELL: 'down', CANCEL: 'neutral' }
/** A session made longer ago than this is not picked up again: its prices and positions are stale. */
const REUSE_MS = 30 * 60_000
/** Quiet time after typing or pasting before the reply is checked. */
const CHECK_DELAY_MS = 600

/** A bot with an API key or connector: a trader runs it (a manual bot has neither). */
export const linked = (m: Pick<ManualBot, 'activeApiKeys' | 'activeConnectors'>): boolean => m.activeApiKeys + m.activeConnectors > 0

/**
 * What a bot's latest session (list shape, newest first) means on load: `done` when it was placed today (ET), `reuse`
 * its id when it is recent and not placed yet, so a reload keeps the prompt and the pasted reply.
 */
export function pickUp(latest: Pick<ManualSession, 'sessionId' | 'status' | 'createdAt' | 'executedAt'> | undefined, nowMs: number): { done: boolean; reuse: string | null } {
  if (!latest?.sessionId || !latest.createdAt) return { done: false, reuse: null }
  if (latest.status === 'EXECUTED') {
    const at = latest.executedAt ?? latest.createdAt
    return { done: etDatePlusDays(Date.parse(at)) === etDatePlusDays(nowMs), reuse: null }
  }
  return { done: false, reuse: nowMs - Date.parse(latest.createdAt) < REUSE_MS ? latest.sessionId : null }
}

/** Copies `text` (a promise is fine: the clipboard write starts inside the click, so a slow request still counts). */
function copyToClipboard(text: Promise<string>): Promise<void> {
  const clip = typeof navigator !== 'undefined' ? navigator.clipboard : undefined
  if (clip?.write && typeof ClipboardItem !== 'undefined') {
    return clip.write([new ClipboardItem({ 'text/plain': text.then((t) => new Blob([t], { type: 'text/plain' })) })])
  }
  if (clip?.writeText) return text.then((t) => clip.writeText(t))
  return Promise.reject(new Error('no clipboard'))
}

function TradeLine({ a }: { a: ManualAction }) {
  const issues = sortIssues(a.issues)
  return (
    <li className={cx('flex flex-wrap items-center gap-x-2 gap-y-0.5 py-1.5 text-caption', !a.executable && 'text-text-3')}>
      <Pill tone={ACTION_TONE[a.action ?? ''] ?? 'neutral'}>{a.action ?? '?'}</Pill>
      <span className="font-semibold text-text-1">{a.symbol ?? a.orderId ?? '--'}</span>
      <span className="text-text-2">{[rowSize(a), rowOrder(a), rowTif(a)].filter(Boolean).join(' · ')}</span>
      <span className="ml-auto">
        {a.result ? (
          <Pill tone={resultTone(a.result)} className="h-auto min-h-5 py-0.5 whitespace-normal normal-case!">
            {resultLabel(a.result)}
          </Pill>
        ) : issues.length === 0 ? (
          <span className="font-semibold text-up">OK</span>
        ) : null}
      </span>
      {issues.length ? (
        <span className="basis-full space-y-0.5">
          {issues.map((i, n) => (
            <span key={`${i.code}-${n}`} className={cx('block text-micro', i.severity === 'ERROR' ? 'text-down' : 'text-warn')}>
              {i.code}
              {i.message ? `: ${i.message}` : ''}
            </span>
          ))}
        </span>
      ) : null}
    </li>
  )
}

/** What "All bots at once" hands one bot: its new session and, once the answer is split, its reply. */
interface Assigned {
  sessionId: string
  reply: string | null
  nonce: number
}

/** One bot: copy its prompt, paste the reply (checked as you go), place the trades; a check when it is done. */
function BotPrompt({
  m,
  index,
  onDone,
  onStatus,
  assigned,
  onReady,
}: {
  m: ManualBot
  index: number
  onDone: (index: number) => void
  onStatus: (handle: string, done: boolean) => void
  assigned?: Assigned
  /** The bot's current session and, when its checked trades can be placed, how to place them. */
  onReady: (handle: string, sessionId: string | null, place: (() => void) | null) => void
}) {
  const handle = m.bot?.handle ?? ''
  const canWrite = m.status === 'ACTIVE'
  const latest = usePlayerManualSessions(handle)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [doneToday, setDoneToday] = useState(false)
  const [picked, setPicked] = useState(false)
  // once, from the bot's latest session: done today, or a recent prompt to keep working on
  useEffect(() => {
    if (picked || latest.isPending) return
    const first = latest.data?.pages[0]?.items?.[0]
    const p = pickUp(first, Date.now())
    setDoneToday(p.done)
    if (p.reuse && !assigned) setSessionId(p.reuse)
    setPicked(true)
  }, [picked, latest.isPending, latest.data, assigned])

  const session = useManualSession(handle, sessionId)
  const s = sessionId ? session.data : undefined
  const create = useCreateManualSession(handle)
  const check = useCheckManualReply(handle, sessionId ?? '')
  const execute = useExecuteManualSession(handle, sessionId ?? '')
  const [text, setText] = useState('')
  const [copied, setCopied] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)
  const checkedText = useRef<string | null>(null)
  // stable, so the prompt is selected once when it shows, not on every render
  const selectAll = useCallback((el: HTMLTextAreaElement | null) => el?.select(), [])

  const executed = s?.status === 'EXECUTED'
  const done = executed || (doneToday && !s)
  useEffect(() => onStatus(handle, done), [handle, done, onStatus])

  // the pasted reply of a picked-up session
  useEffect(() => {
    const reply = s?.untrusted?.reply
    if (reply && !text) {
      setText(reply)
      checkedText.current = reply
    }
    // only when the session (not the text) changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s?.sessionId, s?.untrusted?.reply])

  // check the reply a moment after it changes
  useEffect(() => {
    const value = text.trim()
    if (!sessionId || !value || executed || !canWrite || checkedText.current === text) return
    const t = setTimeout(() => {
      checkedText.current = text
      check.mutate(text)
    }, CHECK_DELAY_MS)
    return () => clearTimeout(t)
    // check.mutate is stable per session
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, sessionId, executed, canWrite])

  const flash = () => {
    setCopyFailed(false)
    setCopied(true)
    setTimeout(() => setCopied(false), 2_000)
  }
  const copyPrompt = () => {
    const ready = s && !executed ? s.untrusted?.prompt : null
    if (ready) {
      copyToClipboard(Promise.resolve(ready)).then(flash, () => setCopyFailed(true))
      return
    }
    const made = create.mutateAsync().then((n) => {
      setSessionId(n.sessionId ?? null)
      setText('')
      checkedText.current = null
      return n.untrusted?.prompt ?? ''
    })
    made.catch((e: unknown) => {
      const d = describeError(e)
      toast({ tone: 'error', title: isApiError(e) && e.code === 'RATE_LIMITED' ? 'Prompt limit reached' : 'No prompt made', body: errorBody(d) ?? d.title })
    })
    copyToClipboard(made).then(flash, () => setCopyFailed(true))
  }

  const rows = useMemo(() => s?.actions ?? [], [s?.actions])
  const tickable = rows.filter(isTickable).map((a) => a.index)
  const replyIssues = sortIssues(s?.issues).filter((i) => i.code !== 'JOURNAL_SKIPPED')
  const checked = s && s.status !== 'PROMPTED' && checkedText.current === text && !check.isPending
  const blocked = hasError(s?.issues)
  const canExecute = Boolean(checked && !blocked && !executed && canWrite && (tickable.length > 0 || (s?.untrusted?.summary && !s.journalNoteId)))
  const place = () =>
    execute.mutate(tickable, {
      onSuccess: (r) => {
        const failed = (r.actions ?? []).filter((a) => tickable.includes(a.index) && a.result?.status === 'FAILED').length
        const placed = (r.actions ?? []).filter((a) => tickable.includes(a.index) && isDone(a)).length
        toast({ tone: failed ? 'warn' : 'up', title: tickable.length ? `@${handle}: ${placed} of ${tickable.length} placed` : `@${handle}: summary posted`, body: failed ? `${failed} failed: see the rows.` : undefined })
        if (!failed) onDone(index)
      },
    })
  const failure = execute.error ? describeError(execute.error) : check.error ? describeError(check.error) : null

  // "All bots at once": a new session from Copy all, then this bot's part of the pasted answer (checked as if typed)
  useEffect(() => {
    if (!assigned) return
    setPicked(true)
    if (assigned.sessionId !== sessionId) {
      setSessionId(assigned.sessionId)
      checkedText.current = null
      setText('')
    }
    if (assigned.reply != null) setText(assigned.reply)
    // only when a new assignment arrives
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assigned?.nonce])

  // tell the screen whether this bot is ready for "Execute all" (a stable function that runs the current place)
  const placeRef = useRef(place)
  placeRef.current = place
  const placeNow = useCallback(() => placeRef.current(), [])
  const ready = canExecute && !execute.isPending
  useEffect(() => onReady(handle, sessionId, ready ? placeNow : null), [handle, sessionId, ready, placeNow, onReady])

  return (
    <li id={`bot-${handle}`} aria-label={`@${handle}`} className={cx('rounded-lg border bg-bg-1 p-4', done ? 'border-up/50' : 'border-divider')}>
      <div className="flex flex-wrap items-center gap-3">
        <span className="relative">
          <Avatar player={m.bot} size={36} />
          {done ? (
            <span aria-label="Done" className="absolute -right-1 -bottom-1 inline-flex size-5 items-center justify-center rounded-full bg-up text-on-updown ring-2 ring-bg-1">
              <IconCheck size={12} />
            </span>
          ) : null}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate font-semibold">{m.bot?.displayName ?? `@${handle}`}</span>
            <AiChip model={m.bot?.aiModel} />
            {linked(m) ? (
              <Pill tone="neutral" title="Also has an API key or connector">
                API
              </Pill>
            ) : null}
            {!canWrite ? <Pill tone="warn">{m.status === 'FROZEN' ? 'Frozen' : 'Disabled'}</Pill> : null}
          </div>
          <span className="text-caption text-text-3">
            <Link to="/bots/$handle" params={{ handle }} search={sessionId ? { session: sessionId } : {}} className="hover:text-accent">
              @{handle}
            </Link>
            {s && !executed ? ` · prompt ${formatTimeEt(s.createdAt)}` : done ? ' · done today' : ''}
          </span>
        </div>
        <Button variant={done ? 'secondary' : 'primary'} size="sm" onClick={copyPrompt} disabled={!canWrite || create.isPending} aria-live="polite">
          {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
          {create.isPending ? 'Making…' : copied ? 'Copied' : done ? 'New prompt' : 'Copy prompt'}
        </Button>
      </div>
      {copyFailed && s?.untrusted?.prompt && !executed ? (
        <div className="mt-2 space-y-1">
          <p role="status" className="text-caption text-warn">
            Copy blocked by the browser: press Ctrl/⌘+C.
          </p>
          <textarea
            aria-label={`Prompt for @${handle}`}
            readOnly
            rows={3}
            value={s.untrusted.prompt}
            ref={selectAll}
            className="w-full resize-y rounded-md border border-border bg-bg-2 px-3 py-2 font-mono text-micro text-text-2 outline-none"
          />
        </div>
      ) : null}
      {s && !executed ? (
        <div className="mt-3 space-y-2">
          <textarea
            aria-label={`Reply for @${handle}`}
            rows={4}
            value={text}
            onChange={(e) => setText(e.target.value)}
            disabled={!canWrite}
            spellCheck={false}
            placeholder="Paste the AI's reply"
            className="w-full resize-y rounded-md border border-border bg-bg-2 px-3 py-2 font-mono text-caption text-text-1 outline-none placeholder:text-text-3 focus:border-accent disabled:opacity-50"
          />
          {check.isPending ? <p className="text-caption text-text-3">Reading the trades…</p> : null}
          {checked && replyIssues.length ? (
            <ul aria-label={`Reply check for @${handle}`} className="space-y-1">
              {replyIssues.map((i, n) => (
                <li key={`${i.code}-${n}`} className={cx('rounded-sm px-2 py-1 text-caption', i.severity === 'ERROR' ? 'bg-down-bg text-down' : 'bg-warn/10 text-warn')}>
                  {issueLine(i)}
                </li>
              ))}
            </ul>
          ) : null}
          {checked && !blocked ? (
            rows.length ? (
              <ul aria-label={`Trades for @${handle}`} className="divide-y divide-divider rounded-md bg-bg-2 px-3">
                {rows.map((a) => (
                  <TradeLine key={a.index} a={a} />
                ))}
              </ul>
            ) : (
              <p className="text-caption text-text-2">No trades this time.</p>
            )
          ) : null}
          {failure ? (
            <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
              {failure.detail ?? failure.title}
            </p>
          ) : null}
          <div className="flex justify-end">
            <Button variant="primary" size="sm" onClick={place} disabled={!canExecute || execute.isPending}>
              {execute.isPending ? 'Placing…' : tickable.length ? `Execute ${tickable.length} trade${tickable.length === 1 ? '' : 's'}` : 'Save'}
            </Button>
          </div>
        </div>
      ) : executed && rows.length ? (
        <ul aria-label={`Trades for @${handle}`} className="mt-3 divide-y divide-divider rounded-md bg-bg-2 px-3">
          {rows.map((a) => (
            <TradeLine key={a.index} a={a} />
          ))}
        </ul>
      ) : null}
    </li>
  )
}

/**
 * All bots at once: Copy all makes a fresh prompt for every active bot and copies them as one message for one chat AI;
 * the AI's whole answer, pasted once, is split by the session each JSON block names and handed to each bot's card
 * (checked there as if typed); Execute all places every bot whose trades checked out.
 */
function AllAtOnce({
  bots,
  states,
  places,
  assign,
}: {
  bots: ManualBot[]
  states: Record<string, { sessionId: string | null; ready: boolean }>
  places: React.RefObject<Record<string, () => void>>
  assign: (parts: Record<string, { sessionId: string; reply: string | null }>) => void
}) {
  const qc = useQueryClient()
  const active = bots.filter((m) => m.status === 'ACTIVE' && m.bot?.handle)
  const [making, setMaking] = useState(false)
  const [copied, setCopied] = useState(false)
  const [fallback, setFallback] = useState<string | null>(null)
  const [answer, setAnswer] = useState('')
  const [result, setResult] = useState<string | null>(null)
  const selectAll = useCallback((el: HTMLTextAreaElement | null) => el?.select(), [])

  const copyAll = () => {
    setMaking(true)
    setFallback(null)
    let combined = ''
    const made = Promise.all(
      active.map((m) => unwrap(api.POST('/api/v1/me/bots/{handle}/manual-sessions', { params: { path: { handle: m.bot!.handle! } } }))),
    ).then((sessions) => {
      const parts: Record<string, { sessionId: string; reply: string | null }> = {}
      const items = sessions.map((s, i) => {
        const m = active[i]!
        const h = m.bot!.handle!
        if (s.sessionId) {
          qc.setQueryData(manualKeys.session(h, s.sessionId), s)
          void qc.invalidateQueries({ queryKey: manualKeys.sessionList(h) })
          parts[h] = { sessionId: s.sessionId, reply: null }
        }
        return { handle: h, name: m.bot?.displayName ?? h, sessionId: s.sessionId ?? '', prompt: s.untrusted?.prompt ?? '' }
      })
      assign(parts)
      combined = combinePrompts(items)
      return combined
    })
    made
      .catch((e: unknown) => {
        const d = describeError(e)
        toast({ tone: 'error', title: isApiError(e) && e.code === 'RATE_LIMITED' ? 'Prompt limit reached' : 'Prompts not made', body: errorBody(d) ?? d.title })
      })
      .finally(() => setMaking(false))
    copyToClipboard(made).then(
      () => {
        setCopied(true)
        setTimeout(() => setCopied(false), 2_000)
      },
      () => made.then(() => setFallback(combined), () => {}),
    )
  }

  // split the pasted answer a moment after it changes, and hand each bot its part
  useEffect(() => {
    if (!answer.trim()) {
      setResult(null)
      return
    }
    const t = setTimeout(() => {
      const sessions: Record<string, string> = {}
      for (const [h, s] of Object.entries(states)) if (s.sessionId) sessions[s.sessionId] = h
      const split = splitReplies(answer, sessions)
      const parts: Record<string, { sessionId: string; reply: string | null }> = {}
      for (const [h, reply] of Object.entries(split.replies)) parts[h] = { sessionId: states[h]!.sessionId!, reply }
      if (Object.keys(parts).length) assign(parts)
      const n = Object.keys(parts).length
      setResult(
        [
          `Found replies for ${n} of ${active.length} bot${active.length === 1 ? '' : 's'}.`,
          split.unknown.length ? `${split.unknown.length} named an old or unknown session (make the prompts again).` : '',
          split.unlabeled ? `${split.unlabeled} JSON block${split.unlabeled === 1 ? '' : 's'} named no session.` : '',
        ]
          .filter(Boolean)
          .join(' '),
      )
    }, CHECK_DELAY_MS)
    return () => clearTimeout(t)
    // split again only when the answer changes (the sessions are read as they are then)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [answer])

  const ready = Object.entries(states).filter(([h, s]) => s.ready && places.current[h])
  const executeAll = () => {
    for (const [h] of ready) places.current[h]?.()
  }

  return (
    <section aria-label="All bots at once" className="space-y-2 rounded-lg border border-accent/40 bg-bg-1 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-section font-semibold">All bots at once</h2>
        <span className="text-caption text-text-3">one prompt, one reply</span>
        <Button variant="primary" size="sm" className="ml-auto" onClick={copyAll} disabled={making || active.length === 0} aria-live="polite">
          {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
          {making ? 'Making…' : copied ? 'Copied' : `Copy all ${active.length} prompts`}
        </Button>
      </div>
      {fallback ? (
        <div className="space-y-1">
          <p role="status" className="text-caption text-warn">
            Copy blocked by the browser: press Ctrl/⌘+C.
          </p>
          <textarea aria-label="All prompts" readOnly rows={3} value={fallback} ref={selectAll} className="w-full resize-y rounded-md border border-border bg-bg-2 px-3 py-2 font-mono text-micro text-text-2 outline-none" />
        </div>
      ) : null}
      <textarea
        aria-label="The AI's whole answer"
        rows={3}
        value={answer}
        onChange={(e) => setAnswer(e.target.value)}
        spellCheck={false}
        placeholder="Paste the AI's whole answer: each bot gets its own part"
        className="w-full resize-y rounded-md border border-border bg-bg-2 px-3 py-2 font-mono text-caption text-text-1 outline-none placeholder:text-text-3 focus:border-accent"
      />
      <div className="flex flex-wrap items-center gap-2">
        {result ? (
          <p role="status" className="text-caption text-text-2">
            {result}
          </p>
        ) : null}
        <Button variant="primary" size="sm" className="ml-auto" onClick={executeAll} disabled={ready.length === 0}>
          Execute all ready ({ready.length})
        </Button>
      </div>
    </section>
  )
}

/**
 * Prompts (`/prompts`): every AI bot I own on one screen. Per bot: copy its prompt, paste the chat AI's reply (the
 * trades are read back as soon as it is pasted), execute them; a check marks the bots that are done.
 */
export function PromptsScreen() {
  const mine = useMyManualBots()
  const [done, setDone] = useState<Record<string, boolean>>({})
  const onStatus = useCallback((h: string, d: boolean) => setDone((prev) => (prev[h] === d ? prev : { ...prev, [h]: d })), [])
  const [showApi, setShowApi] = useState(false)
  const all = mine.data ?? []
  const apiBots = all.filter(linked).length
  const bots = showApi ? all : all.filter((m) => !linked(m))

  // each bot's current session and its "place now" (when its checked trades can be placed), for the batch card
  const [states, setStates] = useState<Record<string, { sessionId: string | null; ready: boolean }>>({})
  const places = useRef<Record<string, () => void>>({})
  const onReady = useCallback((h: string, sessionId: string | null, place: (() => void) | null) => {
    if (place) places.current[h] = place
    else delete places.current[h]
    setStates((prev) => (prev[h]?.sessionId === sessionId && prev[h]?.ready === Boolean(place) ? prev : { ...prev, [h]: { sessionId, ready: Boolean(place) } }))
  }, [])
  const [assigned, setAssigned] = useState<Record<string, Assigned>>({})
  const assign = (parts: Record<string, { sessionId: string; reply: string | null }>) =>
    setAssigned((prev) => {
      const next = { ...prev }
      for (const [h, p] of Object.entries(parts)) next[h] = { ...p, nonce: (prev[h]?.nonce ?? 0) + 1 }
      return next
    })

  // after a bot is done, move to the next one
  const next = (i: number) => {
    const following = bots[i + 1]?.bot?.handle
    if (following) document.getElementById(`bot-${following}`)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  if (mine.isPending) {
    return (
      <div className="space-y-3" aria-busy="true" aria-label="Loading your bots">
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    )
  }
  if (mine.isError) {
    return <EmptyState icon={<IconRobot size={28} />} title="Your bots are unavailable right now" action={<Button size="sm" onClick={() => void mine.refetch()}>Retry</Button>} />
  }
  if (all.length === 0) {
    return <EmptyState icon={<IconRobot size={28} />} title="You don't own any bots" />
  }
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-2">
        <h1 className="text-[20px] leading-7 font-bold tracking-tight">Prompts</h1>
        {bots.length ? <span className="text-caption text-text-3">{bots.filter((m) => done[m.bot?.handle ?? '']).length} of {bots.length} done</span> : null}
        {apiBots ? (
          <button type="button" onClick={() => setShowApi((v) => !v)} className="ml-auto text-caption font-semibold text-accent hover:text-accent-hover">
            {showApi ? 'Hide API bots' : `Show ${apiBots} API bot${apiBots === 1 ? '' : 's'}`}
          </button>
        ) : null}
      </div>
      {bots.length === 0 ? (
        <EmptyState icon={<IconRobot size={28} />} title="No manual bots" body="Your bots trade with an API key." />
      ) : (
        <>
          {bots.length > 1 ? <AllAtOnce bots={bots} states={states} places={places} assign={assign} /> : null}
          <ul className="space-y-3">
            {bots.map((m, i) => (
              <BotPrompt key={m.bot?.handle} m={m} index={i} onDone={next} onStatus={onStatus} assigned={assigned[m.bot?.handle ?? '']} onReady={onReady} />
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
