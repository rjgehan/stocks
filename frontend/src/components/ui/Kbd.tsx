export function Kbd({ children }: { children: React.ReactNode }) {
  return (
    <kbd className="inline-flex h-5 min-w-5 items-center justify-center rounded-sm border border-border bg-bg-1 px-1 font-sans text-micro font-medium text-text-3">
      {children}
    </kbd>
  )
}
