import { Link } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { IconChevronLeft } from '@/components/ui/icons'

/** Section header: a back header on phones (pushed screen), a breadcrumb title elsewhere. */
export function SettingsHeader({ title }: { title: string }) {
  const bp = useBreakpoint()
  if (bp === 'phone') {
    return (
      <div className="-mx-4 -mt-4">
        <PushedHeader title={title} />
      </div>
    )
  }
  return (
    <div className="flex items-center gap-2">
      <Link to="/settings" className="inline-flex items-center gap-1 text-caption text-text-2 hover:text-text-1">
        <IconChevronLeft size={16} /> Settings
      </Link>
      <span className="text-text-3">/</span>
      <h1 className="text-section font-semibold">{title}</h1>
    </div>
  )
}
