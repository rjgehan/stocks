import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '@/lib/api/client'

/** Query keys of the OAuth slice (§17.9), kept here so the shared key table stays untouched. */
export const oauthKeys = {
  request: (id: string) => ['oauthRequest', id] as const,
  connectedApps: ['connectedApps'] as const,
}

/** `GET /me/oauth/requests/{id}`: the consent screen's data (session only). */
export function useOAuthRequest(requestId: string) {
  return useQuery({
    queryKey: oauthKeys.request(requestId),
    enabled: requestId.length > 0,
    retry: false,
    staleTime: Infinity,
    queryFn: () => unwrap(api.GET('/api/v1/me/oauth/requests/{requestId}', { params: { path: { requestId } } })),
  })
}

/** `POST /me/oauth/requests/{id}`: approve or deny; answers the URL the browser must go to. */
export function useDecideOAuthRequest(requestId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { approve: boolean; actAs?: string; scopes?: string[] }) =>
      unwrap(api.POST('/api/v1/me/oauth/requests/{requestId}', { params: { path: { requestId } }, body })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: oauthKeys.connectedApps }),
  })
}

/** `GET /me/connected-apps`: Settings → Connected apps. */
export function useConnectedApps() {
  return useQuery({ queryKey: oauthKeys.connectedApps, queryFn: () => unwrap(api.GET('/api/v1/me/connected-apps')) })
}

/** `DELETE /me/connected-apps/{grantId}`: disconnect one app at once. */
export function useDisconnectApp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (grantId: string) => unwrap(api.DELETE('/api/v1/me/connected-apps/{grantId}', { params: { path: { grantId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: oauthKeys.connectedApps }),
  })
}
