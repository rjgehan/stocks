import { createFileRoute } from '@tanstack/react-router'
import { AppShell } from '@/app/AppShell'
import { requireAuth } from '@/app/RequireAuth'

/** Every signed-in screen: auth guard + the responsive shell (§22.3). */
export const Route = createFileRoute('/_app')({
  beforeLoad: ({ context, location }) => requireAuth(context.queryClient, location.href),
  component: AppShell,
})
