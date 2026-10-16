import { createFileRoute } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { PromptsScreen } from '@/features/manualbot/PromptsScreen'

/** `/prompts` (§20.8): every manual bot I own, prompt and reply side by side (from the League's Create prompts). */
export const Route = createFileRoute('/_app/prompts')({
  staticData: { chrome: 'full', phone: 'pushed', title: 'Prompts' },
  component: PromptsPage,
})

function PromptsPage() {
  const bp = useBreakpoint()
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      {bp === 'phone' ? <PushedHeader title="Prompts" /> : null}
      <div className="mx-auto w-full max-w-4xl px-4 py-4 desktop:px-6">
        <PromptsScreen />
      </div>
    </div>
  )
}
