/**
 * "All bots at once" on the prompts screen: one message holding every manual bot's prompt for a single chat AI, and
 * the AI's one answer split back into each bot's reply. Each prompt asks for a ```json block naming its session
 * (mbs_…), so a block goes to the bot whose session it names; the server takes one block per reply.
 */

export interface BatchItem {
  handle: string
  name: string
  sessionId: string
  prompt: string
}

const SESSION_RE = /"session(?:Id|_id)?"\s*:\s*"(mbs_[0-9A-Za-z]{26})"/
const FENCE_RE = /```[^\n`]*\n([\s\S]*?)(?:```|$)/g

/** One message for one chat AI: a short preface, then each bot's prompt under its own header. */
export function combinePrompts(items: readonly BatchItem[]): string {
  const n = items.length
  const preface = [
    `This message holds ${n} separate prompts for ${n} different players in a paper-trading league, one after the other.`,
    'Treat each one on its own: follow its own instructions and strategy, and never move orders, cash or ideas from one player to another.',
    `Answer all ${n}: for each player, write its short reasoning and then exactly one \`\`\`json block in the format that player's prompt describes, with that prompt's own "session" id.`,
    `Keep the ${n} JSON blocks separate (never one combined block), in the same order as the prompts.`,
  ].join('\n')
  const parts = items.map((it, i) => `\n\n==================== PLAYER ${i + 1} of ${n}: @${it.handle} (${it.name}), session ${it.sessionId} ====================\n\n${it.prompt.trim()}`)
  return preface + parts.join('') + `\n\n==================== END OF THE ${n} PROMPTS ====================\n`
}

export interface Split {
  /** The reply text for each bot (its own JSON block), by handle. */
  replies: Record<string, string>
  /** Sessions named in the answer that belong to no bot of this batch. */
  unknown: string[]
  /** JSON blocks that name no session at all (not sent anywhere). */
  unlabeled: number
}

/** Splits one AI answer into each bot's reply by the session its JSON block names (`sessions`: session id → handle). */
export function splitReplies(text: string, sessions: Readonly<Record<string, string>>): Split {
  const replies: Record<string, string> = {}
  const unknown: string[] = []
  let unlabeled = 0
  for (const m of text.matchAll(FENCE_RE)) {
    const body = m[1] ?? ''
    if (!/"orders"|"actions"|\[/.test(body)) continue // not a reply block (e.g. prose in a code fence)
    const id = SESSION_RE.exec(body)?.[1]
    if (!id) {
      unlabeled += 1
      continue
    }
    const handle = sessions[id]
    if (!handle) {
      if (!unknown.includes(id)) unknown.push(id)
      continue
    }
    replies[handle] = '```json\n' + body.trim() + '\n```'
  }
  return { replies, unknown, unlabeled }
}
