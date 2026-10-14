import { describe, expect, it } from 'vitest'
import type { Me, RunRequest } from '@/lib/api/types'
import { EXPIRED_NOTE, canRunBot, isOpenRequest, runRequestView } from './runNowView'

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

describe('who sees Run now (§20.8)', () => {
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

describe('a run request as the button shows it', () => {
  it('waits for the trader while PENDING', () => {
    const v = runRequestView(req({}), NOW, 'ryan')
    expect(v).toEqual({ label: 'Waiting', tone: 'neutral', line: 'Requested 10:31 AM ET · waiting for the trader', note: 'The trader picks it up within about a minute.' })
    expect(isOpenRequest(req({}))).toBe(true)
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
    const expired = runRequestView(req({ status: 'EXPIRED', finishedAt: '2026-10-14T15:01:00Z', message: 'Expired: …' }), NOW, 'ryan')
    expect(expired.line).toBe('Requested 10:31 AM ET · expired 11:01 AM')
    expect(expired.note).toBe(EXPIRED_NOTE)
    expect(expired.note).toContain('is the claude-trader service running?')
  })

  it('dates a request from another day', () => {
    expect(runRequestView(req({ status: 'DONE', requestedAt: '2026-10-13T14:31:00Z', finishedAt: '2026-10-13T14:35:00Z' }), NOW, 'ryan').line).toBe(
      'Requested Oct 13, 10:31 AM ET · done 10:35 AM',
    )
  })
})
