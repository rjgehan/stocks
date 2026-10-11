/** Friendly text for group-order errors (§11.9, §22.5): the leg's server message without Java field names. */
import { orderErrorText } from '@/lib/api/errors'
import { formatPrice } from '@/lib/format/number'

const LEG_PREFIX = /^(Entry|Take profit|Stop loss|Then order): /

/**
 * `ORDER_GROUP_INVALID` messages are already written for players; a held stop on the wrong side names the entry price;
 * every other leg error goes through the single-order copy (`orderErrorText`) without its "Stop loss: " prefix.
 */
export function groupErrorText(e: { code?: string | null; field?: string | null; message?: string | null }): string {
  const msg = e.message ?? ''
  if (e.code === 'ORDER_GROUP_INVALID') return msg
  const bare = msg.replace(LEG_PREFIX, '')
  if (e.code === 'STOP_PRICE_WRONG_SIDE' && /entry price/.test(bare)) {
    const ref = /\((\d+(?:\.\d+)?)\)/.exec(bare)?.[1]
    return `Must be below the entry price${ref ? ` (${formatPrice(ref)})` : ''}.`
  }
  return orderErrorText({ code: e.code, message: bare })
}
