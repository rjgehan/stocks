/**
 * The API contract the SPA codes against: the GENERATED schema (`schema.d.ts`, from `docs/openapi.json` via
 * `make openapi`) merged with the hand-written PLANNED shapes (`planned.d.ts`) for endpoints whose controllers
 * have not landed yet. For every path and HTTP method, and for every component schema, the generated
 * definition wins; `planned.d.ts` only fills the gaps. Delete planned entries as the backend publishes them.
 */
import type { components as GeneratedComponents, paths as GeneratedPaths } from './schema'
import type { components as PlannedComponents, paths as PlannedPaths } from './planned'

type HttpMethod = 'get' | 'put' | 'post' | 'delete' | 'options' | 'head' | 'patch' | 'trace'

/** The methods a path item really implements (openapi-typescript writes the others as `method?: never`). */
type Implemented<Item> = { [M in HttpMethod]: Item extends { [K in M]: unknown } ? M : never }[HttpMethod]

/** One path item: the generated operations, plus the planned ones for methods the backend does not serve yet. */
type MergeItem<G, P> = Pick<G, Extract<Implemented<G> | Exclude<keyof G, HttpMethod>, keyof G>> &
  Pick<P, Extract<Exclude<Implemented<P>, Implemented<G>>, keyof P>> & {
    [M in Exclude<HttpMethod, Implemented<G> | Implemented<P>>]?: never
  }

export type paths = {
  [K in keyof GeneratedPaths | keyof PlannedPaths]: K extends keyof GeneratedPaths
    ? K extends keyof PlannedPaths
      ? MergeItem<GeneratedPaths[K], PlannedPaths[K]>
      : GeneratedPaths[K]
    : K extends keyof PlannedPaths
      ? PlannedPaths[K]
      : never
}

type GeneratedSchemas = GeneratedComponents['schemas']
type PlannedSchemas = PlannedComponents['schemas']

/** Component schemas by §18.3 name (springdoc strips the Java `Dto` suffix, §18 rule 4). */
export type Schemas = GeneratedSchemas & Omit<PlannedSchemas, keyof GeneratedSchemas>
