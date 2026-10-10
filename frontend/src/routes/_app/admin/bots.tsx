import { createFileRoute } from '@tanstack/react-router'
import { AdminBots } from '@/features/agents/AdminBots'

/** Admin · Bots (Phase 2, §20.6, §21.3): every AI player's runs, spend against the cap and tool calls. */
export const Route = createFileRoute('/_app/admin/bots')({
  component: AdminBots,
})
