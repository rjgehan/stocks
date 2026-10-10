/**
 * Client-side mirrors of the §8 identity column rules (the server decides; these only give early feedback).
 * `player.handle` ^[a-z0-9_]{2,32}$ · `player.display_name` ^[A-Za-z0-9 _.\-]{2,40}$ · `player.ai_model` ≤ 24.
 */
export const HANDLE_RE = /^[a-z0-9_]{2,32}$/
export const DISPLAY_NAME_RE = /^[A-Za-z0-9 _.-]{2,40}$/
export const AI_MODEL_MAX = 24

export const HANDLE_HINT = '2–32 characters: lowercase letters, digits or _.'
export const DISPLAY_NAME_HINT = '2–40 characters: letters, digits, spaces, _ . or -.'

/** Null when the trimmed name is acceptable, otherwise the message to show. */
export function displayNameError(name: string): string | null {
  const v = name.trim()
  if (!v) return 'How should others see you?'
  return DISPLAY_NAME_RE.test(v) ? null : DISPLAY_NAME_HINT
}
