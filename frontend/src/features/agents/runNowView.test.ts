import { describe, expect, it } from 'vitest'
import type { Me, RunAllResult, RunRequest, RunRequestList } from '@/lib/api/types'
import {
  EXPIRED_NOTE,
  type RunAllState,
  canRunBot,
  isOpenRequest,
  runAllHeadline,
  runAllItemView,
  runAllPlan,
  runAllToast,
  runNowBlocked,
  runRequestView,
  serverNow,
} from './runNowView'

const me = (over: Partial<Me> = {}): Me => ({ handle: 'ryan', role: 'PLAYER', status: 'ACTIVE', kind: 'HUMAN', ...over }) as Me

// 2026-10-14 is a Wednesday; 14:31Z is 10:31 AM ET (EDT)
const NOW = Date.parse('2026-10-14T15:00:00Z')
const req = (over: Partial<RunRequest>): RunRequest => ({
  id: 'arq_1',
  handle: 'claude',
  requestedBy: 'ryan',
  requestedAt: '2026-10-14T14:31:00Z',
  dryRun: false,
  status: 'PENDING',
  claimedAt: null,
  finishedAt: null,
  message: null,
  ...over,
})

describe('who sees Run now (§20.9)', () => {
  it('shows it to the bot owner and to an active admin only', () => {
    expect(canRunBot(me(), 'ryan')).toBe(true)
    expect(canRunBot(me(), 'alice')).toBe(false)
    expect(canRunBot(me(), null)).toBe(false)
    expect(canRunBot(me({ role: 'ADMIN' }), 'alice')).toBe(true)
    expect(canRunBot(me({ role: 'ADMIN', status: 'FROZEN' }), 'alice')).toBe(false)
    expect(canRunBot(me({ status: 'FROZEN' }), 'alice')).toBe(false)
    expect(canRunBot(null, 'ryan')).toBe(false)
    expect(canRunBot(undefined, undefined)).toBe(false)
  })
})

const list = (over: Partial<RunRequestList> = {}): RunRequestList => ({
  botStatus: 'ACTIVE',
  traderSeenAt: '2026-10-14T14:59:00Z',
  asOf: '2026-10-14T15:00:00Z',
  requests: [],
  ...over,
})

describe('whether anything would run the bot', () => {
  it('runs an ACTIVE bot that a trader asked for in the last 5 minutes', () => {
    expect(runNowBlocked('claude', list(), NOW)).toBeNull()
    expect(runNowBlocked('claude', list({ traderSeenAt: '2026-10-14T14:55:00Z' }), NOW)).toBeNull()
    expect(runNowBlocked('claude', undefined, NOW)).toBeNull() // not loaded yet: the server decides
  })

  it('explains a bot that no trader serves, and one that is not ACTIVE', () => {
    // e.g. @chatgpt (the agent runner's bot) or a manual bot: no key in the trader's agents file asks for it
    expect(runNowBlocked('chatgpt', list({ traderSeenAt: null }), NOW)).toBe(
      "No claude-trader service has asked for @chatgpt's runs in the last few minutes (it asks every minute for each bot in its agents file), so nothing would pick a run up.",
    )
    expect(runNowBlocked('claude', list({ traderSeenAt: '2026-10-14T14:54:59Z' }), NOW)).toContain('nothing would pick a run up')
    expect(runNowBlocked('claude', list({ botStatus: 'FROZEN' }), NOW)).toBe('@claude is FROZEN: it cannot trade, so it cannot be run.')
  })

  it("judges against the server's clock when the list has it", () => {
    expect(serverNow(list(), 0)).toBe(NOW)
    expect(serverNow(list({ asOf: null }), 42)).toBe(42)
    expect(serverNow(undefined, 42)).toBe(42)
  })
})

describe('a run request as the button shows it', () => {
  it('waits for the trader while PENDING, with a hint once it waits long', () => {
    const v = runRequestView(req({}), Date.parse('2026-10-14T14:32:00Z'), 'ryan')
    expect(v).toEqual({ label: 'Waiting', tone: 'neutral', line: 'Requested 10:31 AM ET · waiting for the trader', note: 'The trader picks it up within about a minute.' })
    expect(isOpenRequest(req({}))).toBe(true)
    expect(runRequestView(req({}), Date.parse('2026-10-14T14:33:01Z'), 'ryan').note).toBe(
      "Not picked up yet: is @claude in the trader's agents file, and is the claude-trader service running?",
    )
  })

  it('runs once claimed, and ends with the times and the trader report', () => {
    expect(runRequestView(req({ status: 'CLAIMED', claimedAt: '2026-10-14T14:31:40Z' }), NOW, 'ryan').line).toBe('Requested 10:31 AM ET · picked up 10:31 AM · running')
    const done = runRequestView(
      req({ status: 'DONE', claimedAt: '2026-10-14T14:31:40Z', finishedAt: '2026-10-14T14:35:10Z', message: 'Check-in done in 3 min 30 s, 12 turns.' }),
      NOW,
      'ryan',
    )
    expect(done.line).toBe('Requested 10:31 AM ET · picked up 10:31 AM · done 10:35 AM')
    expect(done.label).toBe('Done')
    expect(done.tone).toBe('up')
    expect(done.note).toBe('Check-in done in 3 min 30 s, 12 turns.')
    expect(isOpenRequest(req({ status: 'DONE' }))).toBe(false)
  })

  it('names who asked when it was someone else, and marks a dry run', () => {
    expect(runRequestView(req({ dryRun: true }), NOW, 'admin').line).toBe('Dry run requested 10:31 AM ET by @ryan · waiting for the trader')
  })

  it('explains a failure and an expiry', () => {
    const failed = runRequestView(req({ status: 'FAILED', claimedAt: '2026-10-14T14:31:40Z', finishedAt: '2026-10-14T14:33:00Z', message: 'claude exited with code 1' }), NOW, 'ryan')
    expect(failed.line).toBe('Requested 10:31 AM ET · picked up 10:31 AM · failed 10:33 AM')
    expect(failed.tone).toBe('down')
    expect(failed.note).toBe('claude exited with code 1')
    expect(runRequestView(req({ status: 'FAILED' }), NOW).note).toBe('The trader reported a failure.')
    const expired = runRequestView(req({ status: 'EXPIRED', finishedAt: '2026-10-14T15:01:00Z' }), NOW, 'ryan')
    expect(expired.line).toBe('Requested 10:31 AM ET · expired 11:01 AM')
    expect(expired.note).toBe(EXPIRED_NOTE)
    expect(expired.note).toContain('is the claude-trader service running?')
    // the server's reason when it gives one (nobody picked it up in 30 minutes, or the bot was frozen meanwhile)
    const frozen = 'Not run: @claude is FROZEN, so it cannot trade. Request the run again once it is ACTIVE.'
    expect(runRequestView(req({ status: 'EXPIRED', message: frozen }), NOW).note).toBe(frozen)
  })

  it('dates a request from another day', () => {
    expect(runRequestView(req({ status: 'DONE', requestedAt: '2026-10-13T14:31:00Z', finishedAt: '2026-10-13T14:35:00Z' }), NOW, 'ryan').line).toBe(
      'Requested Oct 13, 10:31 AM ET · done 10:35 AM',
    )
  })
})

const loaded = (over: Partial<RunRequestList> = {}): RunAllState => ({ data: list(over), dataUpdatedAt: NOW, isPending: false, isError: false })

const result = (over: Partial<RunAllResult> = {}): RunAllResult => ({
  dryRun: false,
  asOf: '2026-10-14T14:31:00Z',
  requested: 1,
  results: [
    { handle: 'chatgpt', outcome: 'SKIPPED', reason: 'NO_TRADER', message: "No claude-trader service has asked for @chatgpt's runs in the last 5 minutes, so nothing would pick a run up.", request: null },
    { handle: 'claude', outcome: 'REQUESTED', reason: null, message: null, request: req({}) },
    { handle: 'gpt5', outcome: 'ALREADY_OPEN', reason: null, message: '@gpt5 is already running a requested check-in (picked up at 10:30 ET); wait until it reports back.', request: req({ handle: 'gpt5', status: 'CLAIMED' }) },
  ],
  ...over,
})

describe('Run all (§20.9)', () => {
  it('names the bots it would run and why it would not run the others, like the per-bot button', () => {
    const plan = runAllPlan(
      [{ handle: 'claude', status: 'ACTIVE' }, { handle: 'frozen', status: 'FROZEN' }, { handle: 'busy' }, { handle: 'chatgpt' }, { handle: 'iced' }, { handle: 'gpt5' }],
      [
        loaded(),
        { dataUpdatedAt: 0, isPending: true, isError: false },        // never fetched: the admin list says FROZEN
        loaded({ requests: [req({ handle: 'busy', status: 'CLAIMED' })] }),
        loaded({ traderSeenAt: null }),
        loaded({ botStatus: 'FROZEN' }),
        loaded({ requests: [req({ handle: 'gpt5', status: 'DONE' })] }),
      ],
    )
    expect(plan).toEqual({
      run: ['claude', 'gpt5'],
      skipped: [
        { handle: 'frozen', why: 'FROZEN: it cannot trade' },
        { handle: 'busy', why: 'a run is already on its way' },
        { handle: 'chatgpt', why: 'no claude-trader service serves it' },
        { handle: 'iced', why: 'FROZEN: it cannot trade' },
      ],
      unknown: [],
      checking: false,
    })
  })

  it('waits for the lists, and leaves a bot it could not check to the server', () => {
    expect(runAllPlan([{ handle: 'claude' }], [{ dataUpdatedAt: 0, isPending: true, isError: false }]).checking).toBe(true)
    expect(runAllPlan([{ handle: 'claude' }], [{ dataUpdatedAt: 0, isPending: false, isError: true }])).toMatchObject({ run: [], unknown: ['claude'], checking: false })
    // a trader seen 6 minutes before the server's clock no longer serves the bot
    expect(runAllPlan([{ handle: 'claude' }], [loaded({ traderSeenAt: '2026-10-14T14:54:00Z' })]).skipped).toEqual([{ handle: 'claude', why: 'no claude-trader service serves it' }])
  })

  it('sums up what the server did, per bot', () => {
    expect(runAllHeadline(result())).toBe('Run all at 10:31 AM ET: 1 requested · 1 already on its way · 1 skipped')
    expect(runAllHeadline(result({ dryRun: true, requested: 0, results: [] }))).toBe('Dry run all at 10:31 AM ET: you have no bots to run')
    const [skipped, requested, open] = result().results!
    expect(runAllItemView(skipped!)).toEqual({ label: 'Skipped', tone: 'warn', note: expect.stringContaining('No claude-trader service') })
    expect(runAllItemView(requested!)).toEqual({ label: 'Requested', tone: 'accent', note: null })
    expect(runAllItemView({ ...requested!, request: req({ dryRun: true }) }).label).toBe('Dry run requested')
    expect(runAllItemView(open!)).toMatchObject({ label: 'Already on its way', note: expect.stringContaining('already running') })
    expect(runAllToast(result({ requested: 2 })).title).toBe('Run requested for 2 bots')
    expect(runAllToast(result({ dryRun: true })).title).toBe('Dry run requested for 1 bot')
    expect(runAllToast(result({ requested: 0 })).title).toBe('No run requested')
  })
})
