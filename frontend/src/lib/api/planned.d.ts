/**
 * PLANNED API: hand-written §18.3 / §21.1 shapes for endpoints whose controllers have not landed yet.
 *
 * `schema.d.ts` is GENERATED (`make openapi` → `npm run gen:api`) and CI diffs it, so it only covers the
 * controllers that exist. `contract.ts` merges the two: for every path and method the generated operation wins,
 * and this file fills in the rest; the same holds for component schemas. When a controller lands and
 * `make openapi` publishes its operations and DTOs, delete the matching entries here.
 *
 * Empty at M1: every Phase 1 REST endpoint the SPA calls is generated. Add entries here (same rules as before:
 * schema names without the Java `Dto` suffix; RESPONSE members all present, non-primitive ones `T | null`;
 * REQUEST members optional unless `@NotNull`) when a screen needs an endpoint before its controller exists.
 * STOMP payloads are not REST schemas: they live in `types.ts` (`Tick`, `SessionEvent`, `OrderUpdateEvent`).
 */

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface paths {}

export type webhooks = Record<string, never>;

export interface components {
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type
    schemas: {};
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}

export type $defs = Record<string, never>;
