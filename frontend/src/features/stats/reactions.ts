/**
 * Feed reactions (§16.11): the fixed emoji set and the optimistic toggle. One reaction per player per item:
 * picking another emoji moves yours there, picking yours again removes it. Pure; the hooks apply it to the cache.
 */
import type { FeedReaction, Reaction, Reactor } from '@/lib/api/types'

export const REACTIONS: ReadonlyArray<{ value: Reaction; emoji: string; label: string }> = [
  { value: 'ROCKET', emoji: '🚀', label: 'Rocket' },
  { value: 'FIRE', emoji: '🔥', label: 'Fire' },
  { value: 'GEM', emoji: '💎', label: 'Diamond hands' },
  { value: 'CLAP', emoji: '👏', label: 'Clap' },
  { value: 'LAUGH', emoji: '😂', label: 'Laugh' },
  { value: 'SKULL', emoji: '💀', label: 'Skull' },
]

const ORDER = new Map(REACTIONS.map((r, i) => [r.value, i]))
export const EMOJI: Record<Reaction, string> = Object.fromEntries(REACTIONS.map((r) => [r.value, r.emoji])) as Record<Reaction, string>

/** The viewer's current reaction on an item, by handle. */
export function myReaction(reactions: ReadonlyArray<FeedReaction> | null | undefined, handle: string | null | undefined): Reaction | null {
  if (!handle) return null
  for (const r of reactions ?? []) {
    if (r.reaction && (r.players ?? []).some((p) => p.handle === handle)) return r.reaction
  }
  return null
}

/**
 * The item's reactions after the viewer picks `choice` (`set`: the reaction to PUT, or null to DELETE). Keeps the
 * §16.11 order, drops emojis nobody uses any more, and appends the viewer to the end of the chosen list.
 */
export function toggleReaction(reactions: ReadonlyArray<FeedReaction> | null | undefined, me: Reactor, choice: Reaction): { next: FeedReaction[]; set: Reaction | null } {
  const current = myReaction(reactions, me.handle)
  const set = current === choice ? null : choice
  const map = new Map<Reaction, Reactor[]>()
  for (const r of reactions ?? []) {
    if (!r.reaction) continue
    map.set(r.reaction, (r.players ?? []).filter((p) => p.handle !== me.handle))
  }
  if (set) map.set(set, [...(map.get(set) ?? []), me])
  const next = [...map.entries()]
    .filter(([, players]) => players.length > 0)
    .sort(([a], [b]) => (ORDER.get(a) ?? 99) - (ORDER.get(b) ?? 99))
    .map(([reaction, players]) => ({ reaction, count: players.length, players }))
  return { next, set }
}

/** "Bob, Claude and 2 others" for a chip's title. */
export function reactorsLabel(players: ReadonlyArray<Reactor> | null | undefined, max = 3): string {
  const names = (players ?? []).map((p) => p.displayName ?? p.handle ?? '?')
  if (names.length <= max) return names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names.at(-1)}` : (names[0] ?? '')
  return `${names.slice(0, max).join(', ')} and ${names.length - max} other${names.length - max > 1 ? 's' : ''}`
}
