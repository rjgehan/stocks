import { describe, expect, it } from 'vitest'
import { combinePrompts, splitReplies } from './batchPrompts'

const A = 'mbs_01JA0000000000000000000000'
const B = 'mbs_01JB0000000000000000000000'
const C = 'mbs_01JC0000000000000000000000'

describe('all bots at once', () => {
  it('puts every prompt under its own header, after a preface that keeps them apart', () => {
    const text = combinePrompts([
      { handle: 'gem', name: 'Gem', sessionId: A, prompt: 'Prompt for gem.' },
      { handle: 'pennys', name: 'ChatGPT Pennys', sessionId: B, prompt: 'Prompt for pennys.\n' },
    ])
    expect(text).toContain('This message holds 2 separate prompts for 2 different players')
    expect(text).toContain('exactly one ```json block')
    expect(text).toContain(`PLAYER 1 of 2: @gem (Gem), session ${A} ====================\n\nPrompt for gem.`)
    expect(text).toContain(`PLAYER 2 of 2: @pennys (ChatGPT Pennys), session ${B}`)
    expect(text.indexOf('Prompt for gem.')).toBeLessThan(text.indexOf('Prompt for pennys.'))
    expect(text.trimEnd().endsWith('END OF THE 2 PROMPTS ====================')).toBe(true)
  })

  it('splits one answer into each bot\'s reply by the session its block names', () => {
    const answer = [
      'Player 1: buying a little AAPL.',
      '```json',
      `{"format": "stocks.manual.v1", "session": "${A}", "summary": "AAPL.", "orders": [{"action": "BUY", "symbol": "AAPL"}]}`,
      '```',
      'Player 2: nothing today.',
      '```JSON',
      `{"session": "${B}", "summary": "Hold.", "orders": []}`,
      '```',
      'An unknown one:',
      '```json',
      `{"session": "${C}", "orders": []}`,
      '```',
      '```json',
      '{"orders": []}',
      '```',
      '```',
      'just some text',
      '```',
    ].join('\n')
    const s = splitReplies(answer, { [A]: 'gem', [B]: 'pennys' })
    expect(Object.keys(s.replies)).toEqual(['gem', 'pennys'])
    expect(s.replies.gem).toBe(`\`\`\`json\n{"format": "stocks.manual.v1", "session": "${A}", "summary": "AAPL.", "orders": [{"action": "BUY", "symbol": "AAPL"}]}\n\`\`\``)
    expect(s.replies.pennys).toContain(`"session": "${B}"`)
    expect(s.unknown).toEqual([C])
    expect(s.unlabeled).toBe(1)
  })

  it('reads an answer that was cut off before its last fence closed', () => {
    const s = splitReplies('```json\n{"session": "' + A + '", "orders": []}', { [A]: 'gem' })
    expect(s.replies.gem).toContain(A)
  })
})
