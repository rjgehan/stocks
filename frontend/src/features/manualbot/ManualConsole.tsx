import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import {
  useCheckManualReply,
  useCreateManualSession,
  useExecuteManualSession,
  useManualBot,
  useManualSession,
  usePlayerManualSessions,
  useUpdateManualBot,
} from '@/lib/api/hooks/manualBots'
import { isApiError } from '@/lib/api/problem'
import type { ManualAction, ManualBot, ManualIssue, ManualSession } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatInteger, formatPrice, formatQty } from '@/lib/format/number'
import { formatDateTimeEt, formatTimeEt } from '@/lib/format/time'
import { useBreakpoint } from '@/app/useBreakpoint'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { FieldShell, TextField } from '@/components/ui/Field'
import { Pill, type PillTone } from '@/components/ui/Pill'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { IconCheck, IconCopy, IconInfo, IconRobot } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { AI_MODEL_MAX } from '@/features/auth/rules'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { toast } from '@/features/notifications/toastStore'
import {
  SESSION_STATUS_LABEL,
  SESSION_STATUS_TONE,
  hasError,
  isDone,
  isTickable,
  issueLine,
  journalLine,
  placeButton,
  placedCount,
  resultLabel,
  resultTone,
  rowOrder,
  rowPrices,
  rowSize,
  rowTif,
  rowType,
  sortIssues,
} from './manualView'

const CARD = 'rounded-lg border border-divider bg-bg-1 p-4'
const ACTION_TONE: Record<string, PillTone> = { BUY: 'up', SELL: 'down', CANCEL: 'neutral' }
const MAX_REPLY_DEFAULT = 20_000

function CardTitle({ children, aside }: { children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="text-section font-semibold">{children}</h2>
      {aside}
    </div>
  )
}

/** Not a live region (it changes on every keystroke); going over the limit is the field's error (aria-describedby). */
function Counter({ n, max }: { n: number; max: number }) {
  return (
    <span className={cx('text-micro tabular-nums', n > max ? 'font-semibold text-down' : 'text-text-3')}>
      {formatInteger(n)} / {formatInteger(max)}
    </span>
  )
}

/** One reply or row problem: errors in red, warnings in amber; the hint below. */
function IssueItem({ issue }: { issue: ManualIssue }) {
  const error = issue.severity === 'ERROR'
  return (
    <li className={cx('rounded-sm px-2 py-1.5 text-caption', error ? 'bg-down-bg text-down' : 'bg-warn/10 text-warn')}>
      <span className="font-semibold">{error ? 'Error' : 'Warning'}: </span>
      <span className="break-words">{issueLine(issue)}</span>
      {issue.hint ? <span className="block text-text-2">{issue.hint}</span> : null}
    </li>
  )
}

// ------------------------------------------------------------------------------------------- header

function ConsoleHeader({ m, handle }: { m: ManualBot; handle: string }) {
  const bot = m.bot
  const linked = m.activeApiKeys + m.activeConnectors > 0
  return (
    <section aria-label="Bot" className={cx(CARD, 'space-y-3')}>
      <div className="flex flex-wrap items-center gap-3">
        <Avatar player={bot} size={48} />
        <div className="min-w-0 flex-1 basis-[12rem]">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-[20px] leading-7 font-bold tracking-tight">{bot?.displayName ?? `@${handle}`}</h1>
            <AiChip model={bot?.aiModel} />
            <Pill tone="accent">Manual console</Pill>
          </div>
          <p className="text-caption text-text-2">
            @{handle}
            {m.ownerHandle ? ` · run by @${m.ownerHandle}` : ''} · {m.sessionsToday} of {m.sessionsPerDay} prompts today
          </p>
        </div>
        <Link to="/players/$handle" params={{ handle }} className="inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
          Public profile
        </Link>
      </div>
      {m.status && m.status !== 'ACTIVE' ? (
        <p role="alert" className="rounded-sm bg-warn/15 p-2 text-caption text-warn">
          @{handle} is {m.status === 'FROZEN' ? 'frozen' : 'disabled'}: you can look, but not save, prompt or place orders.
        </p>
      ) : null}
      {linked ? (
        <p className="flex items-start gap-2 rounded-sm bg-accent-bg p-2 text-caption text-text-1">
          <IconInfo size={16} className="mt-0.5 shrink-0 text-accent" />
          This bot also trades with an API key or connector; orders from both show on its profile.
        </p>
      ) : null}
    </section>
  )
}

// ------------------------------------------------------------------------------------------- instructions

function InstructionsCard({ m, handle, canWrite }: { m: ManualBot; handle: string; canWrite: boolean }) {
  const update = useUpdateManualBot(handle)
  const [instructions, setInstructions] = useState<string | null>(null)
  const [model, setModel] = useState<string | null>(null)
  const max = m.limits?.maxInstructionsChars ?? 4000
  const textValue = instructions ?? m.instructions ?? ''
  const modelValue = model ?? m.bot?.aiModel ?? ''
  const changed = { instructions: instructions !== null && instructions !== (m.instructions ?? ''), model: model !== null && model.trim() !== (m.bot?.aiModel ?? '') }
  const dirty = changed.instructions || changed.model
  const server = update.error && isApiError(update.error) ? update.error : null
  const modelError = changed.model && !model?.trim() ? 'Enter a model label (1-24 characters).' : (server?.fieldErrors.aiModel ?? null)
  const textError = textValue.length > max ? `At most ${formatInteger(max)} characters.` : (server?.fieldErrors.instructions ?? null)
  const banner = update.error && !(server && server.problem.fields.length) ? describeError(update.error) : null
  const save = (e: React.FormEvent) => {
    e.preventDefault()
    if (!dirty || modelError || textValue.length > max) return
    update.mutate(
      { ...(changed.instructions ? { instructions: textValue } : {}), ...(changed.model ? { aiModel: modelValue.trim() } : {}) },
      {
        onSuccess: () => {
          setInstructions(null)
          setModel(null)
          toast({ tone: 'neutral', title: 'Saved' })
        },
      },
    )
  }
  return (
    <section aria-label="Instructions" className={CARD}>
      <CardTitle>Instructions</CardTitle>
      <form onSubmit={save} className="space-y-3" noValidate>
        <FieldShell label="Instructions for the bot" error={textError} labelAside={<Counter n={textValue.length} max={max} />}>
          {(id, describedBy) => (
            <textarea
              id={id}
              rows={6}
              maxLength={max}
              value={textValue}
              onChange={(e) => setInstructions(e.target.value)}
              disabled={!canWrite}
              aria-invalid={textError ? true : undefined}
              aria-describedby={describedBy}
              placeholder="e.g. Long-term growth; at most 20% in one stock; no penny stocks."
              className={cx(
                'w-full resize-y rounded-md border bg-bg-2 px-3 py-2 text-body text-text-1 outline-none placeholder:text-text-3 focus:border-accent disabled:opacity-50',
                textError ? 'border-down' : 'border-border',
              )}
            />
          )}
        </FieldShell>
        {!textValue ? <p className="text-caption text-text-3">Tell the bot what you want: style, risk, symbols to focus on or avoid.</p> : null}
        <TextField label="Model label" value={modelValue} onChange={(e) => setModel(e.target.value)} maxLength={AI_MODEL_MAX} placeholder="Gemini 3" disabled={!canWrite} error={modelError} hint={`Shown on the profile as “AI · ${modelValue.trim() || 'Gemini 3'}”.`} />
        <p className="text-caption text-text-2">Public: part of every prompt, which every player can read under @{handle}&apos;s History. Key-like strings are removed.</p>
        {banner ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {banner.title}
            {banner.detail ? <span className="block text-text-2">{banner.detail}</span> : null}
          </p>
        ) : null}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-micro text-text-3">
            {m.instructionsUpdatedAt ? `Saved ${formatDateTimeEt(m.instructionsUpdatedAt)}${m.instructionsUpdatedBy ? ` by @${m.instructionsUpdatedBy}` : ''}` : 'Not saved yet'}
          </span>
          <Button type="submit" variant="primary" size="sm" disabled={!canWrite || !dirty || update.isPending}>
            {update.isPending ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </form>
    </section>
  )
}

// ------------------------------------------------------------------------------------------- 1 · Prompt

function PromptCard({
  handle,
  session,
  loading,
  missing,
  canWrite,
  onSession,
}: {
  handle: string
  session: ManualSession | undefined
  loading: boolean
  missing: boolean
  canWrite: boolean
  onSession: (id: string) => void
}) {
  const create = useCreateManualSession(handle)
  const [copied, setCopied] = useState(false)
  const [manualCopy, setManualCopy] = useState(false)
  const [open, setOpen] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const pre = useRef<HTMLPreElement>(null)
  const prompt = session?.untrusted?.prompt ?? ''

  const flashCopied = () => {
    setManualCopy(false)
    setCopied(true)
    setTimeout(() => setCopied(false), 2_000)
  }
  const selectPrompt = () => {
    setOpen(true)
    setManualCopy(true)
    // after the disclosure opened
    setTimeout(() => {
      const node = pre.current
      const selection = window.getSelection()
      if (!node || !selection) return
      const range = document.createRange()
      range.selectNodeContents(node)
      selection.removeAllRanges()
      selection.addRange(range)
    }, 0)
  }
  // Synchronous on the click: the browser only lets a page write to the clipboard during a user gesture.
  const copy = () => {
    try {
      if (!navigator.clipboard?.writeText) {
        selectPrompt()
        return
      }
      navigator.clipboard.writeText(prompt).then(flashCopied, selectPrompt)
    } catch {
      selectPrompt()
    }
  }
  const newPrompt = () => {
    setConfirm(false)
    create.mutate(undefined, {
      onSuccess: (s) => {
        setOpen(false)
        if (s.sessionId) onSession(s.sessionId)
        const text = s.untrusted?.prompt
        try {
          // best effort: the request may have outlived the click's user activation
          if (text && navigator.clipboard?.writeText) navigator.clipboard.writeText(text).then(flashCopied, () => {})
        } catch {
          /* ignore: Copy prompt is right there */
        }
      },
      onError: (e) => {
        const d = describeError(e)
        if (isApiError(e) && e.code === 'RATE_LIMITED') {
          const at = e.problem.retryAfterS != null ? formatTimeEt(Date.now() + e.problem.retryAfterS * 1000) : null
          toast({ tone: 'error', title: 'Prompt limit reached', body: at ? `${d.detail ?? d.title} You can make a new prompt after ${at}.` : (d.detail ?? d.title) })
        } else {
          toast({ tone: 'error', title: 'No prompt made', body: errorBody(d) ?? d.title })
        }
      },
    })
  }
  const discarding = session?.status === 'REPLIED' && !session.executedAt
  const fresh = !session || session.status === 'EXECUTED'

  return (
    <section aria-label="Prompt" className={CARD}>
      <CardTitle>1 · Prompt</CardTitle>
      {loading ? (
        <Skeleton className="h-16 w-full" />
      ) : (
        <div className="space-y-3">
          {missing ? <p className="text-caption text-text-3">That session was not found; make a new prompt.</p> : null}
          {session ? (
            <p className="flex flex-wrap items-center gap-2 text-caption text-text-2">
              <span className="font-mono text-text-1">{session.sessionId}</span>
              <span>made {formatTimeEt(session.createdAt)}</span>
              {session.marketSession ? <Pill tone="neutral">{session.marketSession}</Pill> : null}
              {session.status === 'EXECUTED' ? <Pill tone="up">Placed</Pill> : null}
            </p>
          ) : null}
          {fresh ? (
            <Button variant="primary" onClick={newPrompt} disabled={!canWrite || create.isPending}>
              {create.isPending ? 'Making the prompt…' : 'New prompt'}
            </Button>
          ) : (
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="primary" onClick={copy} disabled={!prompt} aria-live="polite">
                {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                {copied ? 'Copied ✓' : 'Copy prompt'}
              </Button>
              <Button variant="secondary" onClick={() => (discarding ? setConfirm(true) : newPrompt())} disabled={!canWrite || create.isPending}>
                {create.isPending ? 'Making the prompt…' : 'New prompt'}
              </Button>
              {manualCopy ? (
                <span role="status" className="text-caption font-semibold text-warn">
                  Press Ctrl/⌘+C
                </span>
              ) : null}
            </div>
          )}
          {session && prompt ? (
            <details open={open} onToggle={(e) => setOpen(e.currentTarget.open)} className="rounded-md bg-bg-2 px-3 py-2">
              <summary className="flex min-h-11 cursor-pointer items-center text-caption font-semibold text-text-1 tablet:min-h-0">
                Show prompt ({formatInteger(prompt.length)} characters)
              </summary>
              <pre ref={pre} aria-label="Prompt text" className="scrollbar-thin mt-2 max-h-96 overflow-auto font-mono text-micro break-words whitespace-pre-wrap text-text-2">
                {prompt}
              </pre>
            </details>
          ) : null}
          <p className="text-caption text-text-3">Paste it into Gemini, Codex, ChatGPT or any chat. The prompt explains the reply format.</p>
        </div>
      )}
      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} onConfirm={newPrompt} title="Discard this reply?" confirmLabel="New prompt" pending={create.isPending}>
        <p>The checked reply was not placed. A new prompt starts over; this session stays in the history.</p>
      </ConfirmDialog>
    </section>
  )
}

// ------------------------------------------------------------------------------------------- 2 · Reply

function ReplyCard({ handle, session, maxChars, canWrite }: { handle: string; session: ManualSession | undefined; maxChars: number; canWrite: boolean }) {
  const check = useCheckManualReply(handle, session?.sessionId ?? '')
  const [text, setText] = useState<string | null>(null)
  const value = text ?? session?.untrusted?.reply ?? ''
  const executed = session?.status === 'EXECUTED'
  const tooLong = value.length > maxChars
  const checked = session && session.status !== 'PROMPTED'
  const issues = sortIssues(session?.issues)
  const summary = session?.untrusted?.summary
  const failure = check.error ? describeError(check.error) : null
  return (
    <section aria-label="Reply" className={CARD}>
      <CardTitle>2 · Reply</CardTitle>
      {!session ? (
        <p className="text-caption text-text-3">Make a prompt first, then paste the AI&apos;s answer here.</p>
      ) : (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (value.trim() && !tooLong && !executed) check.mutate(value)
          }}
        >
          <FieldShell label="Paste the whole reply here" error={tooLong ? `The reply is longer than ${formatInteger(maxChars)} characters; paste only the part with the JSON block.` : null} labelAside={<Counter n={value.length} max={maxChars} />}>
            {(id, describedBy) => (
              <textarea
                id={id}
                rows={8}
                value={value}
                onChange={(e) => setText(e.target.value)}
                readOnly={executed}
                disabled={!canWrite && !executed}
                aria-describedby={describedBy}
                spellCheck={false}
                placeholder="The AI's whole answer, prose and the ```json block."
                className="w-full resize-y rounded-md border border-border bg-bg-2 px-3 py-2 font-mono text-caption text-text-1 outline-none placeholder:text-text-3 focus:border-accent read-only:opacity-70 disabled:opacity-50"
              />
            )}
          </FieldShell>
          {executed ? <p className="text-caption text-text-3">This session placed orders, so its reply is locked. Make a new prompt to trade again.</p> : null}
          {failure ? (
            <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
              {failure.detail ?? failure.title}
              {failure.hint ? <span className="block text-text-2">{failure.hint}</span> : null}
            </p>
          ) : null}
          <Button type="submit" variant="primary" disabled={!canWrite || executed || !value.trim() || tooLong || check.isPending}>
            {check.isPending ? 'Checking…' : 'Check reply'}
          </Button>
          {checked ? (
            <div className="space-y-3 border-t border-divider pt-3">
              {issues.length ? (
                <ul aria-label="Reply check" className="space-y-1.5">
                  {issues.map((i, n) => (
                    <IssueItem key={`${i.code}-${n}`} issue={i} />
                  ))}
                </ul>
              ) : (
                <p className="text-caption text-up">The reply reads cleanly.</p>
              )}
              {summary ? (
                <figure>
                  <blockquote className="rounded-sm bg-bg-2 px-3 py-2 text-body break-words whitespace-pre-wrap text-text-1">{summary}</blockquote>
                  <figcaption className="mt-1 text-micro text-text-3">Goes to the public journal</figcaption>
                </figure>
              ) : null}
            </div>
          ) : null}
        </form>
      )}
    </section>
  )
}

// ------------------------------------------------------------------------------------------- 3 · Review and place

/** The public rationale, two lines until "More": the toggle shows whenever the clamp actually cuts the text. */
function Rationale({ text }: { text: string | null | undefined }) {
  const [more, setMore] = useState(false)
  const [clipped, setClipped] = useState(false)
  const ref = useRef<HTMLSpanElement>(null)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || more || !text) return
    const measure = () => setClipped(el.scrollHeight > el.clientHeight + 1)
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [text, more])
  if (!text) return <span className="text-text-3">--</span>
  // measured overflow, plus what surely overflows where nothing is measured (no layout)
  const long = clipped || text.length > 140 || text.split('\n').length > 2
  return (
    <span className="block">
      <span ref={ref} className={cx('block break-words whitespace-pre-wrap text-text-2', !more && 'line-clamp-2')}>
        {text}
      </span>
      {more || long ? (
        <button type="button" onClick={() => setMore((v) => !v)} className="text-micro font-semibold text-accent">
          {more ? 'Less' : 'More'}
        </button>
      ) : null}
    </span>
  )
}

function EstimateCell({ a }: { a: ManualAction }) {
  const e = a.estimate
  if (!e) return <span className="text-text-3">--</span>
  return (
    <span className="block text-micro leading-4 whitespace-nowrap text-text-2">
      <span className="block">
        {e.qty ? `${formatQty(e.qty)} @ ` : '@ '}
        {formatPrice(e.price)} = {formatMoney(e.principal)}
      </span>
      <span className="block">
        fees {formatMoney(e.fees?.total)}
        {e.hold && e.hold !== '0.00' ? ` · hold ${formatMoney(e.hold)}` : ''}
      </span>
    </span>
  )
}

function CheckCell({ a }: { a: ManualAction }) {
  const issues = sortIssues(a.issues)
  return (
    <div className="space-y-1">
      {a.result ? (
        <Pill tone={resultTone(a.result)} className="h-auto min-h-5 py-0.5 whitespace-normal normal-case!">
          {resultLabel(a.result)}
        </Pill>
      ) : null}
      {a.result?.orderId ? <span className="block font-mono text-micro text-text-3">{a.result.orderId}</span> : null}
      {issues.length === 0 && !a.result ? <span className="font-semibold text-up">OK</span> : null}
      {issues.length ? (
        <ul className="space-y-1">
          {issues.map((i, n) => (
            <li key={`${i.code}-${n}`} className={cx('text-micro', i.severity === 'ERROR' ? 'text-down' : 'text-warn')}>
              <span className="font-semibold">{i.code}</span>
              {i.message ? `: ${i.message}` : ''}
              {i.hint ? <span className="block text-text-3">{i.hint}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

function RowCheckbox({ a, checked, onToggle, disabled }: { a: ManualAction; checked: boolean; onToggle: () => void; disabled: boolean }) {
  return (
    <input
      type="checkbox"
      aria-label={`Place row ${a.index}`}
      checked={checked}
      disabled={disabled || !isTickable(a)}
      onChange={onToggle}
      className="size-5 accent-[var(--accent)] tablet:size-4"
    />
  )
}

function ActionPill({ a }: { a: ManualAction }) {
  return <Pill tone={ACTION_TONE[a.action ?? ''] ?? 'neutral'}>{a.action ?? '?'}</Pill>
}

function ReviewTable({ rows, ticked, toggle, disabled }: { rows: ManualAction[]; ticked: Set<number>; toggle: (i: number) => void; disabled: boolean }) {
  return (
    <div className="scrollbar-thin overflow-x-auto">
      <table aria-label="Orders in the reply" className="w-full min-w-[40rem] text-caption">
        <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
          <tr className="border-b border-divider">
            <th className="py-1.5 pr-2 font-medium">
              <span className="sr-only">Place</span>
            </th>
            <th className="px-1 font-medium">#</th>
            <th className="px-1 font-medium">Action</th>
            <th className="px-1 font-medium">Symbol</th>
            <th className="px-1 font-medium">Type</th>
            <th className="px-1 text-right font-medium">Qty / $</th>
            <th className="px-1 font-medium">Limit / stop / trail</th>
            <th className="px-1 font-medium">TIF · Ext</th>
            <th className="px-1 font-medium">Estimate</th>
            <th className="pl-1 font-medium">Check</th>
          </tr>
        </thead>
        {/* one row group per order: its columns, then its rationale across the row (it would not fit as a column) */}
        {rows.map((a) => (
          <tbody key={a.index} className={cx('border-b border-divider align-top last:border-0', !a.executable && 'bg-down-bg/30')}>
            <tr aria-label={`Row ${a.index}`}>
              <td className="pt-2 pr-2">
                <RowCheckbox a={a} checked={isTickable(a) && ticked.has(a.index)} onToggle={() => toggle(a.index)} disabled={disabled} />
              </td>
              <td className="px-1 pt-2 text-text-3">
                {a.index}
                {a.line != null ? <span className="block text-micro">L{a.line}</span> : null}
              </td>
              <td className="px-1 pt-2">
                <ActionPill a={a} />
              </td>
              <td className="px-1 pt-2 font-semibold">{a.symbol ?? '--'}</td>
              <td className="px-1 pt-2 whitespace-nowrap">{a.action === 'CANCEL' ? <span className="font-mono text-micro">{a.orderId}</span> : rowType(a)}</td>
              <td className="px-1 pt-2 text-right whitespace-nowrap">{rowSize(a)}</td>
              <td className="px-1 pt-2 whitespace-nowrap">{rowPrices(a)}</td>
              <td className="px-1 pt-2 whitespace-nowrap">{rowTif(a)}</td>
              <td className="px-1 pt-2">{a.action === 'CANCEL' ? null : <EstimateCell a={a} />}</td>
              <td className="min-w-[11rem] pt-2 pl-1">
                <CheckCell a={a} />
              </td>
            </tr>
            <tr>
              <td colSpan={10} className="pt-1 pr-1 pb-2 pl-7">
                <Rationale text={a.rationale} />
              </td>
            </tr>
          </tbody>
        ))}
      </table>
    </div>
  )
}

function ReviewCards({ rows, ticked, toggle, disabled }: { rows: ManualAction[]; ticked: Set<number>; toggle: (i: number) => void; disabled: boolean }) {
  return (
    <ul aria-label="Orders in the reply" className="divide-y divide-divider">
      {rows.map((a) => (
        <li key={a.index} aria-label={`Row ${a.index}`} className="space-y-1.5 py-3">
          <div className="flex items-center gap-2">
            <span className="flex min-h-11 min-w-11 items-center">
              <RowCheckbox a={a} checked={isTickable(a) && ticked.has(a.index)} onToggle={() => toggle(a.index)} disabled={disabled} />
            </span>
            <span className="text-caption text-text-3">#{a.index}</span>
            <ActionPill a={a} />
            <span className="font-semibold">{a.symbol ?? '--'}</span>
            <span className="text-caption text-text-2">{[rowSize(a), rowOrder(a), rowTif(a)].filter(Boolean).join(' · ')}</span>
          </div>
          {a.action === 'CANCEL' && a.orderId ? <p className="font-mono text-micro text-text-3">{a.orderId}</p> : null}
          {a.action !== 'CANCEL' && a.estimate ? (
            <p className="text-micro text-text-2">
              Est. {a.estimate.qty ? `${formatQty(a.estimate.qty)} @ ` : '@ '}
              {formatPrice(a.estimate.price)} · {formatMoney(a.estimate.principal)} · fees {formatMoney(a.estimate.fees?.total)}
              {a.estimate.hold && a.estimate.hold !== '0.00' ? ` · hold ${formatMoney(a.estimate.hold)}` : ''}
            </p>
          ) : null}
          <CheckCell a={a} />
          <div className="text-caption">
            <Rationale text={a.rationale} />
          </div>
        </li>
      ))}
    </ul>
  )
}

function ReviewCard({ handle, session, canWrite }: { handle: string; session: ManualSession | undefined; canWrite: boolean }) {
  const bp = useBreakpoint()
  const execute = useExecuteManualSession(handle, session?.sessionId ?? '')
  const rows = useMemo(() => session?.actions ?? [], [session?.actions])
  // the ticks belong to one checked reply: a new check starts again from the defaults
  const key = `${session?.sessionId ?? ''}:${session?.repliedAt ?? ''}`
  const defaults = useMemo(() => new Set(rows.filter(isTickable).map((a) => a.index)), [rows])
  const [picked, setPicked] = useState<{ key: string; rows: Set<number> } | null>(null)
  const ticked = picked?.key === key ? picked.rows : defaults
  const toggle = (index: number) => {
    const next = new Set(ticked)
    if (next.has(index)) next.delete(index)
    else next.add(index)
    setPicked({ key, rows: next })
  }
  const chosen = rows.filter((a) => isTickable(a) && ticked.has(a.index)).map((a) => a.index)

  if (!session || session.status === 'PROMPTED') {
    return (
      <section aria-label="Review and place" className={CARD}>
        <CardTitle>3 · Review and place</CardTitle>
        <p className="text-caption text-text-3">Check a reply to see its orders here.</p>
      </section>
    )
  }
  if (hasError(session.issues)) {
    return (
      <section aria-label="Review and place" className={CARD}>
        <CardTitle>3 · Review and place</CardTitle>
        <p className="text-caption text-text-2">Fix the reply (or ask the AI again) and check it again.</p>
      </section>
    )
  }
  const button = placeButton(chosen.length, session, handle)
  const journal = session.executedAt ? journalLine(session) : null
  const failure = execute.error ? describeError(execute.error) : null
  const place = () =>
    execute.mutate(chosen, {
      onSuccess: (s) => {
        const placed = (s.actions ?? []).filter((a) => chosen.includes(a.index) && isDone(a)).length
        const failed = (s.actions ?? []).filter((a) => chosen.includes(a.index) && a.result?.status === 'FAILED').length
        toast({ tone: failed ? 'warn' : 'up', title: chosen.length ? `${placed} of ${chosen.length} placed as @${handle}` : 'Summary posted', body: failed ? `${failed} failed: see the rows.` : undefined })
      },
    })
  const disabled = !canWrite || execute.isPending
  return (
    <section aria-label="Review and place" className={CARD}>
      <CardTitle aside={<span className="text-micro text-text-3">{rows.length} row{rows.length === 1 ? '' : 's'}</span>}>3 · Review and place</CardTitle>
      {rows.length === 0 ? (
        <p className="text-caption text-text-2">The reply has no orders: the AI chose not to trade this time.</p>
      ) : bp === 'phone' ? (
        <ReviewCards rows={rows} ticked={ticked} toggle={toggle} disabled={disabled} />
      ) : (
        <ReviewTable rows={rows} ticked={ticked} toggle={toggle} disabled={disabled} />
      )}
      <div className="mt-3 space-y-2 border-t border-divider pt-3">
        {journal ? <p className={cx('text-caption font-semibold', session.journalNoteId ? 'text-up' : 'text-warn')}>{journal}</p> : null}
        {failure ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {failure.detail ?? failure.title}
          </p>
        ) : null}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-caption text-text-3">Orders run in this order: cancels, sells, buys.</p>
          <Button variant="primary" onClick={place} disabled={disabled || !button.enabled}>
            {execute.isPending ? 'Placing…' : button.label}
          </Button>
        </div>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------- recent sessions

function RecentSessions({ handle, current, onSession }: { handle: string; current: string | undefined; onSession: (id: string) => void }) {
  const sessions = usePlayerManualSessions(handle)
  const items = (sessions.data?.pages ?? []).flatMap((p) => p.items ?? [])
  return (
    <section aria-label="Recent sessions" className={CARD}>
      <CardTitle>Recent sessions</CardTitle>
      {sessions.isPending ? (
        <SkeletonRows rows={3} cols={2} />
      ) : sessions.isError ? (
        <p className="text-caption text-text-3">Sessions are unavailable right now.</p>
      ) : items.length === 0 ? (
        <p className="text-caption text-text-3">No prompts yet.</p>
      ) : (
        <ul className="-mx-2 space-y-0.5">
          {items.map((s) => (
            <li key={s.sessionId}>
              <button
                type="button"
                onClick={() => s.sessionId && onSession(s.sessionId)}
                aria-current={s.sessionId === current ? 'true' : undefined}
                className={cx('w-full rounded-md px-2 py-2 text-left hover:bg-bg-2', s.sessionId === current && 'bg-accent-bg')}
              >
                <span className="flex items-center gap-2 text-caption">
                  <span className="font-semibold text-text-1">{formatDateTimeEt(s.createdAt)}</span>
                  {s.status ? <Pill tone={SESSION_STATUS_TONE[s.status]}>{SESSION_STATUS_LABEL[s.status]}</Pill> : null}
                  <span className="ml-auto shrink-0 text-text-3">{placedCount(s)} placed</span>
                </span>
                <span className="mt-0.5 block truncate text-caption text-text-2">{s.untrusted?.summary ?? 'No summary'}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {sessions.hasNextPage ? (
        <Button size="sm" className="mt-2" onClick={() => void sessions.fetchNextPage()} disabled={sessions.isFetchingNextPage}>
          Load more
        </Button>
      ) : null}
    </section>
  )
}

// ------------------------------------------------------------------------------------------- the console

/**
 * The manual console of an AI bot (§20.8, `/bots/$handle`): the owner (or the admin) writes the bot's instructions,
 * makes a prompt, pastes the chat AI's reply back, checks it (every row is previewed live) and places the ticked
 * orders as the bot. Instructions, prompts, replies and results are public under the bot's History.
 */
export function ManualConsole({ handle, sessionId, onSession }: { handle: string; sessionId: string | undefined; onSession: (id: string) => void }) {
  const bot = useManualBot(handle)
  const session = useManualSession(handle, sessionId)
  const m = bot.data

  if (bot.isError) {
    const e = bot.error
    const code = isApiError(e) ? e.code : null
    const detail = isApiError(e) ? e.problem.detail : null
    const title =
      code === 'PLAYER_NOT_FOUND'
        ? `No bot @${handle}`
        : code === 'FORBIDDEN'
          ? (detail ?? `Only the bot's owner (or the admin) can operate @${handle}.`)
          : 'The console is unavailable right now'
    return (
      <EmptyState
        icon={<IconRobot size={28} />}
        title={title}
        action={
          code === 'FORBIDDEN' ? (
            <Link to="/players/$handle" params={{ handle }} className="text-accent">
              Open @{handle}&apos;s profile
            </Link>
          ) : code === 'PLAYER_NOT_FOUND' ? (
            <Link to="/league" className="text-accent">
              Back to the league
            </Link>
          ) : (
            <Button size="sm" onClick={() => void bot.refetch()}>
              Retry
            </Button>
          )
        }
      />
    )
  }
  if (!m) {
    return (
      <div className="space-y-4" aria-busy="true" aria-label="Loading the console">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    )
  }
  const canWrite = m.status === 'ACTIVE'
  const current = sessionId ? session.data : undefined
  const missing = Boolean(sessionId) && session.isError
  return (
    <div className="space-y-4">
      <ConsoleHeader m={m} handle={handle} />
      {/* phone and tablet: one column (instructions first, sessions last); desktop: prompt and reply on the left,
          instructions and sessions on the right, the review table full width below */}
      <div className="grid grid-cols-1 items-start gap-4 desktop:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="desktop:col-start-2 desktop:row-start-1">
          <InstructionsCard m={m} handle={handle} canWrite={canWrite} />
        </div>
        <div className="min-w-0 space-y-4 desktop:col-start-1 desktop:row-span-2 desktop:row-start-1">
          <PromptCard handle={handle} session={current} loading={Boolean(sessionId) && session.isPending} missing={missing} canWrite={canWrite} onSession={onSession} />
          <ReplyCard key={current?.sessionId ?? 'none'} handle={handle} session={current} maxChars={m.limits?.maxReplyChars ?? MAX_REPLY_DEFAULT} canWrite={canWrite} />
        </div>
        <div className="min-w-0 desktop:col-span-2 desktop:row-start-3">
          <ReviewCard key={current?.sessionId ?? 'none'} handle={handle} session={current} canWrite={canWrite} />
        </div>
        <div className="desktop:col-start-2 desktop:row-start-2">
          <RecentSessions handle={handle} current={sessionId} onSession={onSession} />
        </div>
      </div>
    </div>
  )
}
