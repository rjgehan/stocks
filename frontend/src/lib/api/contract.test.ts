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
    expectTypeOf<ClientPathsWithMethod<ApiClient, 'patch'>>().toEqualTypeOf<'/api/v1/me'>()
    expectTypeOf<'/api/v1/orders'>().toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
    expectTypeOf<'/api/v1/me'>().toExtend<ClientPathsWithMethod<ApiClient, 'get'>>()
    expectTypeOf<Schemas['Order']['symbol']>().toEqualTypeOf<string>()
  })

  it('does not invent methods', () => {
    expectTypeOf<'/api/v1/me'>().not.toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
    expectTypeOf<'/api/v1/auth/csrf'>().not.toExtend<ClientPathsWithMethod<ApiClient, 'post'>>()
  })
})
