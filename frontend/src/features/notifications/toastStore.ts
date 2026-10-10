import { create } from 'zustand'

export type ToastTone = 'up' | 'down' | 'neutral' | 'error' | 'warn'

export interface ToastItem {
  id: number
  tone: ToastTone
  title: string
  body?: string
}

interface ToastState {
  toasts: ToastItem[]
  push: (t: Omit<ToastItem, 'id'>) => number
  dismiss: (id: number) => void
}

let nextId = 1

export const useToastStore = create<ToastState>()((set, get) => ({
  toasts: [],
  push: (t) => {
    const id = nextId++
    set({ toasts: [...get().toasts.slice(-3), { ...t, id }] })
    setTimeout(() => get().dismiss(id), 4_500)
    return id
  },
  dismiss: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
}))

export const toast = (t: Omit<ToastItem, 'id'>): number => useToastStore.getState().push(t)
