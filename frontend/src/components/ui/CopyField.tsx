import { useState } from 'react'
import { cx } from './cx'
import { IconCheck, IconCopy } from './icons'

/** Copies text to the clipboard; falls back to selecting a hidden textarea where the async API is unavailable. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* fall through */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return ok
  } catch {
    return false
  }
}

/** A read-only value with a Copy button ("Copied" for 2 s). `multiline` wraps long snippets. */
export function CopyField({ value, label, multiline, className, mono = true }: { value: string; label: string; multiline?: boolean; className?: string; mono?: boolean }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    if (await copyText(value)) {
      setCopied(true)
      setTimeout(() => setCopied(false), 2_000)
    }
  }
  return (
    <div className={cx('flex items-stretch overflow-hidden rounded-md border border-border bg-bg-2', className)}>
      <code
        aria-label={label}
        className={cx(
          'min-w-0 flex-1 px-3 py-2 text-caption text-text-1 select-all',
          mono && 'font-mono',
          multiline ? 'break-all whitespace-pre-wrap' : 'truncate whitespace-nowrap',
        )}
      >
        {value}
      </code>
      <button
        type="button"
        onClick={() => void copy()}
        aria-label={copied ? `${label} copied` : `Copy ${label}`}
        className={cx('flex shrink-0 items-center gap-1.5 border-l border-border px-3 text-caption font-semibold transition-colors', copied ? 'text-up' : 'text-accent hover:bg-bg-3')}
      >
        {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
        {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  )
}
