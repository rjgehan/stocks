import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/account/activity')({
  validateSearch: (s: Record<string, unknown>): { types?: string } => ({ types: typeof s.types === 'string' ? s.types : undefined }),
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="Activity"
      milestone="M1"
      description="Fills, deposits with their memo and a “by admin” badge, dividends, splits, GFVs, resets and “Admin viewed your live account” entries, newest first."
    />
  ),
})
