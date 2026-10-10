/**
 * Mock mode entry point (§22.9): `VITE_API_MOCK=1` (npm run dev:mock) routes the REST client and the
 * realtime hub here. No extra dependencies; never bundled into normal builds.
 */
import { MockServer } from './server'

let server: MockServer | null = null

export function mockServer(): MockServer {
  server ??= new MockServer()
  return server
}

export function mockFetch(request: Request): Promise<Response> {
  return mockServer().handle(request)
}

export { createMockDriver } from './realtime'
