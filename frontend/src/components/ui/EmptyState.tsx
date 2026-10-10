export function EmptyState({ icon, title, body, action }: { icon?: React.ReactNode; title: string; body?: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-10 text-center">
      {icon ? <div className="mb-1 text-text-3">{icon}</div> : null}
      <p className="text-section font-semibold text-text-1">{title}</p>
      {body ? <p className="max-w-xs text-caption text-text-2">{body}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
