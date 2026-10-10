/**
 * Recent-authentication prompt (§17.1 chain 1, §21.1 "R" routes): when an admin write (or a personal API key)
 * answers 403 `RECENT_AUTH_REQUIRED`, the app asks for the password once (`ReauthDialog`, mounted by the shell),
 * calls `POST /auth/reauth`, and retries the original request. Cancelling the prompt rethrows the 403.
 */
import { create } from 'zustand'
import { isApiError } from './problem'

interface ReauthState {
  pending: Array<(ok: boolean) => void>
  open: boolean
  ask: () => Promise<boolean>
  settle: (ok: boolean) => void
}

export const useReauthStore = create<ReauthState>()((set, get) => ({
  pending: [],
  open: false,
  ask: () =>
    new Promise<boolean>((resolve) => {
      set({ pending: [...get().pending, resolve], open: true })
    }),
  settle: (ok) => {
    const waiting = get().pending
    set({ pending: [], open: false })
    for (const resolve of waiting) resolve(ok)
  },
}))

/** Runs `fn`; on `RECENT_AUTH_REQUIRED` prompts for the password and retries once. */
export async function withRecentAuth<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn()
  } catch (e) {
    if (!isApiError(e) || e.code !== 'RECENT_AUTH_REQUIRED') throw e
    const ok = await useReauthStore.getState().ask()
    if (!ok) throw e
    return fn()
  }
}
