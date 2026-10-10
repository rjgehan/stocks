import { useQueryClient } from '@tanstack/react-query'
import { useMe } from '@/lib/api/hooks/identity'
import { applyFeedReactions, useReact } from '@/lib/api/hooks/stats'
import type { FeedItem, Reaction, Reactor } from '@/lib/api/types'
import { Popover } from '@/components/ui/Popover'
import { cx } from '@/components/ui/cx'
import { EMOJI, REACTIONS, myReaction, reactorsLabel, toggleReaction } from './reactions'

/**
 * Reactions under a feed item (§16.11, §22.4 Phase 2): a chip per emoji in use ("🚀 3", the viewer's own highlighted
 * and `aria-pressed`, the title lists who reacted) and a "+" picker with the six emojis. One reaction per player per
 * item; picking yours again removes it. Optimistic, then the server's answer (also pushed live on `/topic/feed`).
 */
export function ReactionBar({ item }: { item: FeedItem }) {
  const qc = useQueryClient()
  const { data: me } = useMe()
  const react = useReact()
  const reactions = item.reactions ?? []
  const mine = myReaction(reactions, me?.handle)
  const canReact = Boolean(item.id && me?.handle)

  const pick = (choice: Reaction) => {
    if (!item.id || !me?.handle) return
    const self: Reactor = { handle: me.handle, displayName: me.displayName ?? me.handle, kind: me.kind ?? 'HUMAN' }
    const previous = reactions
    const { next, set } = toggleReaction(reactions, self, choice)
    applyFeedReactions(qc, item.id, next)
    react.mutate({ itemId: item.id, reaction: set, previous })
  }

  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-1" aria-label="Reactions" role="group">
      {reactions.map((r) =>
        r.reaction ? (
          <button
            key={r.reaction}
            type="button"
            aria-pressed={r.reaction === mine}
            disabled={!canReact}
            onClick={() => pick(r.reaction!)}
            title={reactorsLabel(r.players)}
            aria-label={`${REACTIONS.find((x) => x.value === r.reaction)?.label ?? r.reaction} ${r.count}${r.reaction === mine ? ', yours' : ''}`}
            className={cx(
              'inline-flex h-8 min-w-11 items-center justify-center gap-1 rounded-full border px-2 text-caption font-semibold tabular-nums transition-colors tablet:h-6 tablet:min-w-0',
              r.reaction === mine ? 'border-accent bg-accent-bg text-accent' : 'border-divider bg-bg-2 text-text-2 hover:border-border hover:text-text-1',
            )}
          >
            <span aria-hidden="true">{EMOJI[r.reaction]}</span>
            {r.count}
          </button>
        ) : null,
      )}
      {canReact ? (
        <Popover
          label="Pick a reaction"
          align="start"
          className="min-w-0 px-1"
          renderTrigger={(props) => (
            <button
              type="button"
              {...props}
              aria-label="Add a reaction"
              className="inline-flex h-8 min-w-11 items-center justify-center rounded-full border border-dashed border-divider px-2 text-caption text-text-3 hover:border-border hover:text-text-1 tablet:h-6 tablet:min-w-8"
            >
              <span aria-hidden="true">☺+</span>
            </button>
          )}
        >
          {(close) => (
            <div className="flex gap-0.5" role="group" aria-label="Reactions to pick">
              {REACTIONS.map((r) => (
                <button
                  key={r.value}
                  type="button"
                  aria-label={r.label}
                  aria-pressed={r.value === mine}
                  onClick={() => {
                    pick(r.value)
                    close()
                  }}
                  className={cx('flex size-11 items-center justify-center rounded-md text-[20px] hover:bg-bg-2 tablet:size-9', r.value === mine && 'bg-accent-bg')}
                >
                  {r.emoji}
                </button>
              ))}
            </div>
          )}
        </Popover>
      ) : null}
    </div>
  )
}
