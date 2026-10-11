/**
 * Group-order (Phase 2, §11.9) aliases over the generated contract: BRACKET, OCO, OTO and OTOCO. Kept beside
 * `types.ts` so the group feature does not touch the shared alias list.
 */
import type { Schemas as S } from './contract'

type NN<T> = Exclude<T, null | undefined>

export type OrderGroup = S['OrderGroup']
export type OrderGroupRef = S['OrderGroupRef']
export type GroupLegRequest = S['GroupLegRequest']
export type PlaceOrderGroupRequest = S['PlaceOrderGroupRequest']
export type PlaceOrderGroupResponse = S['PlaceOrderGroupResponse']
export type ReplaceOrderGroupRequest = S['ReplaceOrderGroupRequest']
export type GroupPreview = S['GroupPreview']
export type GroupLegPreview = S['GroupLegPreview']
export type OrderGroupRule = S['OrderGroupRule']

export type OrderGroupType = NN<OrderGroup['type']>
export type OrderGroupRole = NN<OrderGroupRef['role']>
export type OrderGroupStatus = NN<OrderGroup['status']>
