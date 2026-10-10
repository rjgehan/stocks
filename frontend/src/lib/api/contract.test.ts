import { describe, expectTypeOf, it } from 'vitest'
import type { paths, Schemas } from './contract'
import type { components, operations } from './schema'
import type { ClientPathsWithMethod } from 'openapi-fetch'
import type { ApiClient } from './client'

// Type-level checks (run by `npm run typecheck`; `skipLibCheck` does not apply to this file).
describe('contract merge (generated schema.d.ts wins, planned.d.ts fills the gaps)', () => {
  it('uses the generated operation where the backend publishes one', () => {
    expectTypeOf<paths['/api/v1/me']['get']>().toEqualTypeOf<operations['getMe']>()
    expectTypeOf<paths['/api/v1/auth/login']['post']>().toEqualTypeOf<operations['login']>()
    expectTypeOf<Schemas['Me']>().toEqualTypeOf<components['schemas']['Me']>()
  })

  it('keeps planned methods and paths the backend does not serve yet', () => {
    expectTypeOf<'/api/v1/me'>().toExtend<ClientPathsWithMethod<ApiClient, 'patch'>>()
    expectTypeOf<'/api/v1/orders'>().toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
    expectTypeOf<'/api/v1/me'>().toExtend<ClientPathsWithMethod<ApiClient, 'get'>>()
    expectTypeOf<'/api/v1/admin/cash-transfers'>().toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
    expectTypeOf<'/api/v1/watchlists/{id}/symbols/{symbol}'>().toExtend<ClientPathsWithMethod<ApiClient, 'delete'>>()
  })

  it('planned response shapes follow §18 rule 2 (non-primitive members are nullable, primitives are not)', () => {
    expectTypeOf<Schemas['Order']['symbol']>().toEqualTypeOf<string | null>()
    expectTypeOf<Schemas['Order']['extendedHours']>().toEqualTypeOf<boolean>()
    expectTypeOf<Schemas['Account']['runNo']>().toEqualTypeOf<number>()
    expectTypeOf<Schemas['Quote']['bidSize']>().toEqualTypeOf<number | null>()
    expectTypeOf<Schemas['LeaderboardRow']['return']>().toEqualTypeOf<string | null>()
  })

  it('does not invent methods', () => {
    expectTypeOf<'/api/v1/me'>().not.toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
    expectTypeOf<'/api/v1/auth/csrf'>().not.toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
    expectTypeOf<'/api/v1/account'>().not.toExtend<ClientPathsWithMethod<ApiClient, 'delete'>>()
  })
})
