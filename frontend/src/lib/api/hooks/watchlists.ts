import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'

export function useWatchlists() {
  return useQuery({ queryKey: queryKeys.watchlists, queryFn: () => unwrap(api.GET('/api/v1/watchlists')), staleTime: 60_000 })
}

export function useAddToWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, symbol }: { id: string; symbol: string }) =>
      unwrap(api.POST('/api/v1/watchlists/{id}/symbols', { params: { path: { id } }, body: { symbol } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.watchlists }),
  })
}
