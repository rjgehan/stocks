import type { ReactNode } from 'react'
import { LogoMark } from '@/components/ui/icons'

/** Login and signup frame (§22.2): a centered card on tablet and desktop, full screen on phones. */
export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="flex min-h-dvh items-stretch justify-center bg-bg-0 pt-safe pb-safe tablet:items-center tablet:p-6">
      <div className="relative flex w-full flex-col bg-bg-1 tablet:max-w-[400px] tablet:overflow-hidden tablet:rounded-lg tablet:border tablet:border-divider tablet:shadow-2xl">
        <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-0 h-40 bg-gradient-to-b from-up-bg to-transparent opacity-70" />
        <div className="relative flex flex-1 flex-col gap-4 p-6">
          <div className="mb-1 flex items-center gap-3">
            <LogoMark size={40} />
            <div>
              <h1 className="text-[22px] leading-7 font-bold tracking-tight">{title}</h1>
              <p className="text-caption text-text-2">{subtitle}</p>
            </div>
          </div>
          {children}
        </div>
        {footer ? <div className="relative border-t border-divider px-6 py-4 text-center text-caption text-text-2">{footer}</div> : null}
      </div>
    </div>
  )
}
