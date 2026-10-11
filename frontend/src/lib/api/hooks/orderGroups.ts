/**
 * Group orders (Phase 2, §11.9): preview, place, read, replace and cancel `/api/v1/order-groups`. Each leg is also an
 * ordinary order, so every write refreshes the order lists, the account and the positions like a single order does.
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import type { OrderGroup, PlaceOrderGroupRequest, ReplaceOrderGroupRequest } from '../groupTypes'
import { queryKeys } from '../queryKeys'

export const groupKeys = {
  group: (groupId: string) => ['orderGroup', groupId] as const,
  preview: (request: unknown) => ['orderGroupPreview', request] as const,
}

function afterGroupChange(qc: QueryClient, group?: OrderGroup | null) {
  void qc.invalidateQueries({ queryKey: ['orders'] })
  void qc.invalidateQueries({ queryKey: ['order'] })
  void qc.invalidateQueries({ queryKey: queryKeys.account })
  void qc.invalidateQueries({ queryKey: queryKeys.positions })
  if (group?.groupId) qc.setQueryData(groupKeys.group(group.groupId), group)
}

/** `POST /order-groups/preview` (always 200; `valid=false` lists the first error, its field named after the leg). */
export function useGroupPreview(request: PlaceOrderGroupRequest | null) {
  return useQuery({
    queryKey: groupKeys.preview(request),
    queryFn: () => unwrap(api.POST('/api/v1/order-groups/preview', { body: request! })),
    enabled: request !== null,
    staleTime: 2_000,
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  })
}

/** `GET /order-groups/{groupId}`: the group with its current legs (list shape). */
export function useOrderGroup(groupId: string | null | undefined) {
  return useQuery({
    queryKey: groupKeys.group(groupId ?? ''),
    queryFn: () => unwrap(api.GET('/api/v1/order-groups/{groupId}', { params: { path: { groupId: groupId! } } })),
    enabled: Boolean(groupId),
    staleTime: 3_000,
    // a working group changes by itself (fills, OCO cancels): keep the linked legs fresh while it is active
    refetchInterval: (q) => (q.state.data?.status === 'ACTIVE' ? 5_000 : false),
  })
}

export function usePlaceGroup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (request: PlaceOrderGroupRequest) => unwrap(api.POST('/api/v1/order-groups', { body: request })),
    onSuccess: (res) => afterGroupChange(qc, res.group),
  })
}

export function useReplaceGroup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ groupId, request }: { groupId: string; request: ReplaceOrderGroupRequest }) =>
      unwrap(api.PATCH('/api/v1/order-groups/{groupId}', { params: { path: { groupId } }, body: request })),
    onSuccess: (res) => afterGroupChange(qc, res.group),
  })
}

export function useCancelGroup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (groupId: string) => unwrap(api.DELETE('/api/v1/order-groups/{groupId}', { params: { path: { groupId } } })),
    onSuccess: (group) => afterGroupChange(qc, group),
  })
}
