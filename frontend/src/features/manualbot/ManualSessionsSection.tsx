import { useEffect, useRef, useState } from 'react'
import { useRouterState } from '@tanstack/react-router'
import { useManualSession, usePlayerManualSessions } from '@/lib/api/hooks/manualBots'
import type { ManualSession } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { Pill, type PillTone } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { SESSION_STATUS_LABEL, resultLabel, resultTone, rowSize } from './manualView'

const ACTION_TONE: Record<string, PillTone> = { BUY: 'up', SELL: 'down', CANCEL: 'neutral' }

/** A lazily fetched plain-text block of the session (the prompt or the pasted reply). */
function Disclosure({ label, open, onToggle, text, loading }: { label: string; open: boolean; onToggle: (open: boolean) => void; text: string | null | undefined; loading: boolean }) {
  return (
    <details open={open} onToggle={(e) => onToggle(e.currentTarget.open)} className="rounded-md bg-bg-2 px-3 py-2">
      <summary className="flex min-h-11 cursor-pointer items-center text-caption font-semibold text-text-1 tablet:min-h-0">{label}</summary>
      {open ? (
        loading ? (
          <Skeleton className="mt-2 h-16 w-full" />
        ) : (
          <pre aria-label={`${label} text`} className="scrollbar-thin mt-2 max-h-96 overflow-auto font-mono text-micro break-words whitespace-pre-wrap text-text-2">
            {text ?? 'Not available.'}
          </pre>
        )
      ) : null}
    </details>
  )
}

function SessionCard({ handle, s, focused }: { handle: string; s: ManualSession; focused: boolean }) {
  // the linked session opens expanded: its reply, or its prompt when nothing was pasted yet
  const [promptOpen, setPromptOpen] = useState(focused && !s.repliedAt)
  const [replyOpen, setReplyOpen] = useState(focused && Boolean(s.repliedAt))
  const ref = useRef<HTMLLIElement>(null)
  // the detail (with the prompt and the reply) only once a disclosure opens, or for the linked session
  const detail = useManualSession(handle, s.sessionId, promptOpen || replyOpen || focused)
  const full = detail.data ?? s
  const actions = full.actions ?? []
  // focused later (a "Manual console" pill on the same History while this card is shown): open it the same way
  const [wasFocused, setWasFocused] = useState(focused)
  if (focused !== wasFocused) {
    setWasFocused(focused)
    if (focused && s.repliedAt) setReplyOpen(true)
    else if (focused) setPromptOpen(true)
  }
  useEffect(() => {
    if (focused) ref.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
  }, [focused])
  return (
    <li ref={ref} id={s.sessionId ?? undefined} aria-label={`Manual session ${s.sessionId ?? ''}`} aria-current={focused ? 'true' : undefined} className={cx('space-y-2 px-4 py-3', focused && 'rounded-md bg-accent-bg/40 ring-2 ring-accent')}>
      <p className="text-caption text-text-2">
        {formatDateTimeEt(full.createdAt)} · {full.aiModel ?? 'AI'} · pasted by @{full.operatorHandle} · {full.status ? SESSION_STATUS_LABEL[full.status] : ''}
      </p>
      {full.untrusted?.summary ? (
        <p className="rounded-sm bg-bg-2 px-2 py-1 text-caption break-words whitespace-pre-wrap text-text-1">{full.untrusted.summary}</p>
      ) : null}
      {actions.length ? (
        <div className="scrollbar-thin overflow-x-auto">
          <table aria-label={`Orders of ${s.sessionId ?? 'the session'}`} className="w-full min-w-[30rem] text-caption">
            <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
              <tr className="border-b border-divider">
                <th className="py-1 pr-2 font-medium">#</th>
                <th className="px-1 font-medium">Action</th>
                <th className="px-1 font-medium">Symbol</th>
                <th className="px-1 text-right font-medium">Qty / $</th>
                <th className="px-1 font-medium">Result</th>
                <th className="pl-1 font-medium">Order</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-divider">
              {actions.map((a) => (
                <tr key={a.index}>
                  <td className="py-1 pr-2 text-text-3">{a.index}</td>
                  <td className="px-1 py-1">
                    <Pill tone={ACTION_TONE[a.action ?? ''] ?? 'neutral'}>{a.action}</Pill>
                  </td>
                  <td className="px-1 py-1 font-semibold">{a.symbol ?? '--'}</td>
                  <td className="px-1 py-1 text-right">{rowSize(a)}</td>
                  <td className="px-1 py-1">
                    {a.result ? (
                      <Pill tone={resultTone(a.result)} className="h-auto min-h-5 py-0.5 whitespace-normal normal-case!">
                        {resultLabel(a.result)}
                      </Pill>
                    ) : (
                      <span className="text-text-3">not placed</span>
                    )}
                  </td>
                  <td className="py-1 pl-1 font-mono text-micro text-text-3">{a.result?.orderId ?? (a.action === 'CANCEL' ? a.orderId : null) ?? '--'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <div className="grid gap-2 tablet:grid-cols-2">
        <Disclosure label="Prompt" open={promptOpen} onToggle={setPromptOpen} text={detail.data?.untrusted?.prompt} loading={detail.isPending} />
        {s.repliedAt ? <Disclosure label="Reply" open={replyOpen} onToggle={setReplyOpen} text={detail.data?.untrusted?.reply} loading={detail.isPending} /> : null}
      </div>
    </li>
  )
}

/**
 * "Manual sessions" under an AI player's History (§20.8): every prompt its owner made, with the pasted reply, the
 * parsed orders and their results, all public. `?session=mbs_…` (from a manual order's pill) puts that session first,
 * expanded and highlighted. Renders nothing for a bot that was never driven by hand.
 */
export function ManualSessionsSection({ handle, session }: { handle: string; session?: string }) {
  const fromUrl = useRouterState({
    select: (st) => {
      const v = (st.location.search as Record<string, unknown>).session
      return typeof v === 'string' && v.startsWith('mbs_') ? v : undefined
    },
  })
  const focus = session ?? fromUrl
  const list = usePlayerManualSessions(handle)
  const items = (list.data?.pages ?? []).flatMap((p) => p.items ?? [])
  const onPage = focus ? items.find((s) => s.sessionId === focus) : undefined
  const linked = useManualSession(handle, focus, Boolean(focus) && !onPage)
  const first = onPage ?? linked.data
  if (!first && items.length === 0) return null
  const ordered = first ? [first, ...items.filter((s) => s.sessionId !== first.sessionId)] : items
  return (
    <section aria-label="Manual sessions" className="rounded-lg border border-divider bg-bg-1">
      <div className="px-4 pt-3">
        <h2 className="text-section font-semibold">Manual sessions</h2>
        <p className="text-caption text-text-3">Prompts the owner copied to a chat AI, its pasted replies and the orders placed from them.</p>
      </div>
      <ul className="mt-2 divide-y divide-divider">
        {ordered.map((s) => (
          <SessionCard key={s.sessionId} handle={handle} s={s} focused={Boolean(focus) && s.sessionId === focus} />
        ))}
      </ul>
      {list.hasNextPage ? (
        <div className="flex justify-center border-t border-divider p-3">
          <Button size="sm" onClick={() => void list.fetchNextPage()} disabled={list.isFetchingNextPage}>
            Load more
          </Button>
        </div>
      ) : null}
    </section>
  )
}
