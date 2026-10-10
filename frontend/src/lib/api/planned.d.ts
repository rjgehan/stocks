/**
 * PLANNED API: hand-written §18.3 / §21.1 shapes for endpoints whose controllers have not landed yet.
 *
 * `schema.d.ts` is GENERATED (`make openapi` → `npm run gen:api`) and CI diffs it, so it only covers the
 * controllers that exist. `contract.ts` merges the two: for every path and method the generated operation wins,
 * and this file fills in the rest; the same holds for component schemas. When a controller lands and
 * `make openapi` publishes its operations and DTOs, delete the matching entries here.
 *
 * Every Phase 1 REST endpoint the SPA calls is generated (no planned paths). Add entries here (same rules as before:
 * schema names without the Java `Dto` suffix; RESPONSE members all present, non-primitive ones `T | null`;
 * REQUEST members optional unless `@NotNull`) when a screen needs an endpoint before its controller exists.
 * STOMP payloads are not REST schemas: they live in `types.ts` (`Tick`, `SessionEvent`, `OrderUpdateEvent`).
 *
 * Planned schemas (Phase 1 corporate actions, §14 / §18.3):
 *  - `DividendDetail`: the `Activity.detail` of a cash-dividend entitlement (`type: "DIVIDEND"`). The activity list
 *    shows an ACCRUED (ex-date, counted in equity as a receivable) or VOID (reset before the payable date)
 *    entitlement as a DIVIDEND row with this detail; the PAID dividend is the DIVIDEND ledger row (detail null, or
 *    this detail with `status: "PAID"`). The SPA reads it through `ActivityDetailView` (types.ts), so the generated
 *    `Activity.detail` union does not need to list it until the backend publishes it.
 */

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface paths {}

export type webhooks = Record<string, never>;

export interface components {
    schemas: {
        DividendDetail: {
            /** @enum {string} */
            type: "DIVIDEND";
            corporateActionId: string | null;
            /** @enum {string|null} */
            status: "ACCRUED" | "PAID" | "VOID" | null;
            /** Format: decimal (shares entitled at the ex-date) */
            qty: string | null;
            /** Format: decimal (dollars per share) */
            cashRate: string | null;
            /** Format: date */
            exDate: string | null;
            /** Format: date */
            payableDate: string | null;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}

export type $defs = Record<string, never>;
