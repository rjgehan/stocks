/**
 * Watchlists (§18.2): list, create, rename/reorder (`PUT` with the full ordered symbol list), delete, add and
 * remove a symbol. Writes are optimistic: the cached lists change first and roll back on an error.
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { Watchlist } from '../types'

export function useWatchlists() {
  return useQuery({ queryKey: queryKeys.watchlists, queryFn: () => unwrap(api.GET('/api/v1/watchlists')), staleTime: 60_000 })
}

type Lists = Watchlist[] | undefined

async function optimistic(qc: QueryClient, update: (lists: Watchlist[]) => Watchlist[]) {
  await qc.cancelQueries({ queryKey: queryKeys.watchlists })
  const prev = qc.getQueryData<Watchlist[]>(queryKeys.watchlists)
  if (prev) qc.setQueryData<Watchlist[]>(queryKeys.watchlists, update(prev))
  return { prev }
}

function rollback(qc: QueryClient, ctx: { prev: Lists } | undefined) {
  if (ctx?.prev) qc.setQueryData(queryKeys.watchlists, ctx.prev)
}

function replaceList(qc: QueryClient, list: Watchlist) {
  qc.setQueryData<Watchlist[]>(queryKeys.watchlists, (lists) => lists?.map((w) => (w.id === list.id ? list : w)))
}

export function useAddToWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, symbol }: { id: string; symbol: string }) =>
      unwrap(api.POST('/api/v1/watchlists/{id}/symbols', { params: { path: { id } }, body: { symbol } })),
    onMutate: ({ id, symbol }) =>
      optimistic(qc, (lists) =>
        lists.map((w) => (w.id === id && !(w.symbols ?? []).includes(symbol) ? { ...w, symbols: [...(w.symbols ?? []), symbol] } : w)),
      ),
    onError: (_e, _v, ctx) => rollback(qc, ctx),
    onSuccess: (list) => replaceList(qc, list),
  })
}

export function useRemoveFromWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, symbol }: { id: string; symbol: string }) =>
      unwrap(api.DELETE('/api/v1/watchlists/{id}/symbols/{symbol}', { params: { path: { id, symbol } } })),
    onMutate: ({ id, symbol }) =>
      optimistic(qc, (lists) => lists.map((w) => (w.id === id ? { ...w, symbols: (w.symbols ?? []).filter((s) => s !== symbol) } : w))),
    onError: (_e, _v, ctx) => rollback(qc, ctx),
    onSuccess: (list) => replaceList(qc, list),
  })
}

/** Rename and/or reorder: `PUT /watchlists/{id} {name, symbols}` (array order = sort order). */
export function useUpdateWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, name, symbols }: { id: string; name: string; symbols: string[] }) =>
      unwrap(api.PUT('/api/v1/watchlists/{id}', { params: { path: { id } }, body: { name, symbols } })),
    onMutate: ({ id, name, symbols }) => optimistic(qc, (lists) => lists.map((w) => (w.id === id ? { ...w, name, symbols } : w))),
    onError: (_e, _v, ctx) => rollback(qc, ctx),
    onSuccess: (list) => replaceList(qc, list),
  })
}

export function useCreateWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ name, symbols }: { name: string; symbols?: string[] }) =>
      unwrap(api.POST('/api/v1/watchlists', { body: symbols ? { name, symbols } : { name } })),
    onSuccess: (list) => qc.setQueryData<Watchlist[]>(queryKeys.watchlists, (lists) => [...(lists ?? []), list]),
  })
}

export function useDeleteWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE('/api/v1/watchlists/{id}', { params: { path: { id } } })),
    onMutate: (id) => optimistic(qc, (lists) => lists.filter((w) => w.id !== id)),
    onError: (_e, _v, ctx) => rollback(qc, ctx),
  })
}
