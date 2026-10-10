import { createFileRoute } from '@tanstack/react-router'
import { PositionsTable } from '@/features/account/PositionsTable'

export const Route = createFileRoute('/_app/account/positions')({
  component: () => (
    <div className="-mx-4 bg-bg-1 tablet:mx-0 tablet:rounded-lg tablet:border tablet:border-divider">
      <PositionsTable />
    </div>
  ),
})
