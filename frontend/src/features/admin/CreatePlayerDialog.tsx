import { useState } from 'react'
import { describeError } from '@/lib/api/errors'
import { useCreatePlayer, useLeagueSettings } from '@/lib/api/hooks/admin'
import { useMe } from '@/lib/api/hooks/identity'
import { isApiError } from '@/lib/api/problem'
import type { CreatePlayerResponse } from '@/lib/api/types'
import { isDecimal, lt } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Checkbox, TextField } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { AI_MODEL_MAX, HANDLE_HINT, HANDLE_RE, displayNameError } from '@/features/auth/rules'


/**
 * Create a human or an AI player (§21.1 `POST /admin/players`, R): the server creates the player, the funded account,
 * the default watchlist and, for a bot, an API key whose token is shown once.
 */
export function CreatePlayerDialog({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (res: CreatePlayerResponse) => void }) {
  const create = useCreatePlayer()
  const settings = useLeagueSettings()
  const { data: me } = useMe()
  const [kind, setKind] = useState<'HUMAN' | 'AI'>('AI')
  const [handle, setHandle] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [aiModel, setAiModel] = useState('')
  const [owner, setOwner] = useState('')
  const [startingCash, setStartingCash] = useState('')
  const [issueKey, setIssueKey] = useState(true)
  const [touched, setTouched] = useState(false)
  const defaultCash = settings.data?.['league.defaultStartingCash'] ?? '500.00'

  const local: Record<string, string> = {}
  if (!HANDLE_RE.test(handle)) local.handle = HANDLE_HINT
  const nameError = displayNameError(displayName)
  if (nameError) local.displayName = nameError
  if (kind === 'HUMAN' && password.length < 10) local.password = 'At least 10 characters (share it with them privately).'
  if (startingCash && (!isDecimal(startingCash) || lt(startingCash, '1.00'))) local.startingCash = 'At least $1.00.'
  const server = create.error && isApiError(create.error) ? create.error : null
  const fieldErr = (f: string) => server?.fieldErrors[f] ?? (server?.code === 'HANDLE_TAKEN' && f === 'handle' ? 'That handle is taken.' : null) ?? (touched ? (local[f] ?? null) : null)
  const banner = create.error && !(server && (server.problem.fields.length || server.code === 'HANDLE_TAKEN')) ? describeError(create.error) : null

  const reset = () => {
    setHandle('')
    setDisplayName('')
    setPassword('')
    setAiModel('')
    setOwner('')
    setStartingCash('')
    setIssueKey(true)
    setTouched(false)
    create.reset()
  }
  const close = () => {
    reset()
    onClose()
  }
  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(local).length) return
    create.mutate(
      {
        kind,
        handle,
        displayName: displayName.trim(),
        ...(kind === 'HUMAN' ? { password } : { aiModel: aiModel.trim() || null, ownerHandle: owner.trim() || null, issueKey }),
        ...(startingCash ? { startingCash } : {}),
      },
      {
        onSuccess: (res) => {
          reset()
          onCreated(res)
        },
      },
    )
  }

  return (
    <Dialog open={open} onClose={close} title="Add a player">
      <form onSubmit={submit} className="space-y-3 p-4" noValidate>
        <Segmented
          label="Player kind"
          block
          value={kind}
          onChange={setKind}
          options={[
            { value: 'AI', label: 'AI bot' },
            { value: 'HUMAN', label: 'Human' },
          ]}
        />
        <p className="text-caption text-text-3">
          {kind === 'AI'
            ? 'Bots trade with an API key through REST or MCP and must give a rationale for each order.'
            : 'Humans usually join through an invite link; create one here only if you want to set their password yourself.'}
        </p>
        <TextField
          label="Handle"
          prefix="@"
          value={handle}
          onChange={(e) => setHandle(e.target.value.toLowerCase().replace(/\s/g, ''))}
          autoCapitalize="none"
          spellCheck={false}
          maxLength={32}
          error={fieldErr('handle')}
          placeholder={kind === 'AI' ? 'claude' : 'alice'}
        />
        <TextField label="Display name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={40} error={fieldErr('displayName')} placeholder={kind === 'AI' ? 'Claude' : 'Alice'} />
        {kind === 'HUMAN' ? (
          <TextField label="Password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} error={fieldErr('password')} />
        ) : (
          <>
            <div className="grid grid-cols-2 gap-3">
              <TextField label="Model label" value={aiModel} onChange={(e) => setAiModel(e.target.value)} maxLength={AI_MODEL_MAX} placeholder="Claude" hint="Shown as “AI · Claude”." />
              <TextField label="Owner" prefix="@" value={owner} onChange={(e) => setOwner(e.target.value.toLowerCase())} placeholder={me?.handle ?? 'you'} hint="Who runs this bot." />
            </div>
            <Checkbox checked={issueKey} onChange={setIssueKey}>
              Issue an API key now (all scopes, shown once)
            </Checkbox>
          </>
        )}
        <TextField
          label="Starting cash"
          prefix="$"
          inputMode="decimal"
          value={startingCash}
          onChange={(e) => setStartingCash(e.target.value.replace(/[^\d.]/g, ''))}
          placeholder={defaultCash}
          hint={`Leave empty for the league default (${formatMoney(defaultCash)}).`}
          error={fieldErr('startingCash')}
        />
        {banner ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {banner.title}
            {banner.detail ? <span className="block text-text-2">{banner.detail}</span> : null}
          </p>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block disabled={create.isPending}>
          {create.isPending ? 'Creating…' : kind === 'AI' ? 'Create bot' : 'Create player'}
        </Button>
      </form>
    </Dialog>
  )
}
