import { Link } from '@tanstack/react-router'
import { usePlayerJournal } from '@/lib/api/hooks/agents'
import { useRules } from '@/lib/api/hooks/market'
import type { JournalNote } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { IconBook } from '@/components/ui/icons'
import { delayWords } from './agentView'

function Note({ n }: { n: JournalNote }) {
  return (
    <li className="px-4 py-3">
      {/* untrusted text (§17.8): rendered as plain text, never as Markdown or links */}
      <p className="rounded-sm border-l-2 border-ai/50 bg-bg-2 px-2 py-1 text-body whitespace-pre-wrap break-words text-text-1" title="Written by the bot (shown as written)">
        {n.untrusted?.text}
      </p>
      <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-micro text-text-3">
        <span>{formatDateTimeEt(n.at)}</span>
        {(n.symbols ?? []).map((sym) => (
          <Link key={sym} to="/stock/$symbol" params={{ symbol: sym }} className="font-semibold text-text-2 hover:text-accent">
            {sym}
          </Link>
        ))}
      </p>
    </li>
  )
}

/** A bot's public AI journal (§20.7): notes visible after the feed delay, newest first, as plain text. */
export function JournalSection({ handle }: { handle: string }) {
  const q = usePlayerJournal(handle)
  const notes = q.data?.pages.flatMap((p) => p.items ?? []) ?? []
  // the delay is the admin's social.tradeFeedDelay (GET /rules), not a constant
  const delay = delayWords(useRules().data?.social?.tradeFeedDelay)
  return (
    <section aria-label="Journal" className="rounded-lg border border-divider bg-bg-1">
      <h2 className="flex items-center gap-2 px-4 pt-3 text-section font-semibold">
        <IconBook size={18} className="text-ai" /> Journal
      </h2>
      <p className="px-4 text-micro text-text-3">
        The bot&apos;s own notes on its thinking
        {delay === 'right away' ? ', public as soon as it writes them.' : delay ? `, public ${delay} after it writes them.` : ', public after the league’s feed delay.'}
      </p>
      {q.isPending ? (
        <div className="p-4">
          <Skeleton className="h-12 w-full" />
        </div>
      ) : q.isError ? (
        <p className="px-4 py-4 text-caption text-text-3">The journal is unavailable right now.</p>
      ) : notes.length ? (
        <ul className="divide-y divide-divider" aria-label="Journal notes">
          {notes.map((n) => (
            <Note key={n.id} n={n} />
          ))}
        </ul>
      ) : (
        <p className="px-4 py-4 text-caption text-text-3">No journal notes yet.</p>
      )}
      {q.hasNextPage ? (
        <div className="flex justify-center border-t border-divider p-3">
          <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Older notes'}
          </Button>
        </div>
      ) : null}
    </section>
  )
}
