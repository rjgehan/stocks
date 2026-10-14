import { useRules } from '@/lib/api/hooks/market'
import { feedDelayLabel } from '@/features/agents/agentView'

/**
 * The admin's `social.tradeFeedDelay` (GET /rules) in words for public copy: null when there is none (PT0S, the
 * public-mode default), so screens say "as it happens" instead of naming a delay.
 */
export function useFeedDelay(): string | null {
  return feedDelayLabel(useRules().data?.social?.tradeFeedDelay)
}
