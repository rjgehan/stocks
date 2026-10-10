import type { PlayerSummary } from '@/lib/api/types'
import { Pill } from '@/components/ui/Pill'
import { cx } from '@/components/ui/cx'

const HUES = [210, 160, 280, 20, 330, 45, 190, 120]

/** A deterministic avatar from the handle (no uploaded images in Phase 1). */
export function Avatar({ player, size = 32 }: { player: Pick<PlayerSummary, 'handle' | 'displayName' | 'kind' | 'avatarSeed'> | null | undefined; size?: number }) {
  const seed = player?.avatarSeed ?? player?.handle ?? '?'
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0
  const hue = HUES[h % HUES.length]!
  const initials = (player?.displayName ?? player?.handle ?? '?')
    .split(/\s+/)
    .map((w) => w.charAt(0))
    .join('')
    .slice(0, 2)
    .toUpperCase()
  return (
    <span
      aria-hidden="true"
      className={cx('inline-flex shrink-0 items-center justify-center rounded-full font-bold text-white', player?.kind === 'AI' && 'ring-2 ring-ai ring-offset-1 ring-offset-bg-1')}
      style={{ width: size, height: size, fontSize: Math.round(size * 0.38), background: `hsl(${hue} 55% 42%)` }}
    >
      {initials}
    </span>
  )
}

/** "AI · Claude" chip (§22.1 PlayerBadge). */
export function AiChip({ model }: { model: string | null | undefined }) {
  return (
    <Pill tone="ai" title="An AI player trading through the API">
      AI{model ? ` · ${model}` : ''}
    </Pill>
  )
}
