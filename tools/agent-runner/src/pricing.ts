// Per-model token prices for the per-bot daily dollar cap (ARCHITECTURE §20.5). Cost = reported token usage × price.
//
// The built-in table was checked on 2026-10-10 against the vendors' official pricing pages:
//   Anthropic: https://platform.claude.com/docs/en/about-claude/pricing (standard API rates, global routing)
//   OpenAI:    https://developers.openai.com/api/docs/pricing (Standard tier, text tokens)
// Prices change: override one bot's model with AGENT_PRICE_CLAUDE / AGENT_PRICE_CHATGPT (see parsePriceOverride).
// A model that is neither in this table nor overridden cannot be capped, so that bot does not run.

import { parsePerMillionTokens, scalePrice } from './money.js';

export type Vendor = 'anthropic' | 'openai';

/** Pico-dollars per token. */
export interface PriceTier {
  input: bigint;
  cachedInput: bigint;
  cacheWrite5m: bigint;
  cacheWrite1h: bigint;
  output: bigint;
}

export interface ModelPrice {
  /** The table entry (or override) the price came from. */
  pricedAs: string;
  source: 'builtin' | 'env';
  base: PriceTier;
  /** Higher prices for a request whose prompt (input + cache reads + cache writes) exceeds the threshold. */
  longContext: { aboveInputTokens: number; tier: PriceTier } | null;
}

/** Token usage of one model response, normalized across vendors. `input` excludes cached and cache-write tokens. */
export interface TokenUsage {
  input: number;
  cacheRead: number;
  cacheWrite5m: number;
  cacheWrite1h: number;
  output: number;
}

export const ZERO_USAGE: TokenUsage = { input: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, output: 0 };

export function addUsage(a: TokenUsage, b: TokenUsage): TokenUsage {
  return {
    input: a.input + b.input,
    cacheRead: a.cacheRead + b.cacheRead,
    cacheWrite5m: a.cacheWrite5m + b.cacheWrite5m,
    cacheWrite1h: a.cacheWrite1h + b.cacheWrite1h,
    output: a.output + b.output,
  };
}

export function promptTokens(u: TokenUsage): number {
  return u.input + u.cacheRead + u.cacheWrite5m + u.cacheWrite1h;
}

/** Cost of one response in pico-dollars. */
export function costOf(usage: TokenUsage, price: ModelPrice): bigint {
  const tier =
    price.longContext && promptTokens(usage) > price.longContext.aboveInputTokens ? price.longContext.tier : price.base;
  return (
    BigInt(usage.input) * tier.input +
    BigInt(usage.cacheRead) * tier.cachedInput +
    BigInt(usage.cacheWrite5m) * tier.cacheWrite5m +
    BigInt(usage.cacheWrite1h) * tier.cacheWrite1h +
    BigInt(usage.output) * tier.output
  );
}

// ------------------------------------------------------------------------------------------------ built-in table

/** USD per million tokens: [input, cached input (cache hit), 5-minute cache write, 1-hour cache write, output]. */
type Row = readonly [string, string, string, string, string];

function toTier([input, cached, write5m, write1h, output]: Row): PriceTier {
  return {
    input: parsePerMillionTokens(input),
    cachedInput: parsePerMillionTokens(cached),
    cacheWrite5m: parsePerMillionTokens(write5m),
    cacheWrite1h: parsePerMillionTokens(write1h),
    output: parsePerMillionTokens(output),
  };
}

interface Entry {
  base: Row;
  long?: { above: number; row: Row };
}

// Anthropic: base input, cache hits, 5m cache writes, 1h cache writes, output. Claude 4.6+ models bill the full 1M
// context at standard rates; only Claude Haiku 5.5 is priced higher for prompts over 100,000 tokens.
const ANTHROPIC: Record<string, Entry> = {
  'claude-fable-5-1': { base: ['10', '0.25', '12.50', '20', '50'] },
  'claude-mythos-5-1': { base: ['10', '0.25', '12.50', '20', '50'] },
  'claude-fable-5': { base: ['10', '1', '12.50', '20', '50'] },
  'claude-mythos-5': { base: ['10', '1', '12.50', '20', '50'] },
  'claude-opus-5-5': { base: ['4', '0.20', '5', '8', '20'] },
  'claude-opus-5': { base: ['5', '0.50', '6.25', '10', '25'] },
  'claude-opus-4-8': { base: ['5', '0.50', '6.25', '10', '25'] },
  'claude-opus-4-7': { base: ['5', '0.50', '6.25', '10', '25'] },
  'claude-opus-4-6': { base: ['5', '0.50', '6.25', '10', '25'] },
  'claude-opus-4-5': { base: ['5', '0.50', '6.25', '10', '25'] },
  'claude-sonnet-5-5': { base: ['2', '0.10', '2.50', '4', '10'] },
  'claude-sonnet-5': { base: ['2', '0.20', '2.50', '4', '10'] },
  'claude-sonnet-4-6': { base: ['3', '0.30', '3.75', '6', '15'] },
  'claude-sonnet-4-5': { base: ['3', '0.30', '3.75', '6', '15'] },
  'claude-haiku-5-5': {
    base: ['0.10', '0.01', '0.125', '0.20', '0.50'],
    long: { above: 100_000, row: ['0.50', '0.05', '0.625', '1', '2.50'] },
  },
  'claude-haiku-4-5': { base: ['1', '0.10', '1.25', '2', '5'] },
};

// OpenAI: input, cached input, cache writes (the same value in both write columns), output. The page lists cache-write
// prices only for the GPT-5.6 and GPT-6 families; elsewhere a write is billed as plain input, and a model without a
// cached-input price bills cached tokens as plain input. Long context = more than 272K input tokens.
function oa(input: string, cached: string | null, write: string | null, output: string): Row {
  const w = write ?? input;
  return [input, cached ?? input, w, w, output];
}
const LONG_OA = 272_000;
const OPENAI: Record<string, Entry> = {
  'gpt-6-astra': {
    base: oa('10', '1', '12.50', '50'),
    long: { above: LONG_OA, row: oa('20', '2', '25', '75') },
  },
  'gpt-6.1-sol': {
    base: oa('2', '0.10', '2.50', '10'),
    long: { above: LONG_OA, row: oa('4', '0.20', '5', '15') },
  },
  'gpt-6-sol': {
    base: oa('2', '0.20', '2.50', '10'),
    long: { above: LONG_OA, row: oa('4', '0.40', '5', '15') },
  },
  'gpt-6-luna': {
    base: oa('0.10', '0.01', '0.125', '0.50'),
    long: { above: LONG_OA, row: oa('0.20', '0.02', '0.25', '0.75') },
  },
  // GPT-5.6 Sol: promotional pricing "at least through November 21, 2026" per the pricing page.
  'gpt-5.6-sol': {
    base: oa('4', '0.40', '5', '20'),
    long: { above: LONG_OA, row: oa('8', '0.80', '10', '30') },
  },
  'gpt-5.6-terra': {
    base: oa('2', '0.20', '2.50', '12'),
    long: { above: LONG_OA, row: oa('4', '0.40', '5', '18') },
  },
  'gpt-5.6-luna': {
    base: oa('0.20', '0.02', '0.25', '1.20'),
    long: { above: LONG_OA, row: oa('0.40', '0.04', '0.50', '1.80') },
  },
  'gpt-5.5': { base: oa('5', '0.50', null, '30'), long: { above: LONG_OA, row: oa('10', '1', null, '45') } },
  'gpt-5.5-pro': { base: oa('30', null, null, '180'), long: { above: LONG_OA, row: oa('60', null, null, '270') } },
  'gpt-5.4': { base: oa('2.50', '0.25', null, '15'), long: { above: LONG_OA, row: oa('5', '0.50', null, '22.50') } },
  'gpt-5.4-mini': { base: oa('0.75', '0.075', null, '4.50') },
  'gpt-5.4-nano': { base: oa('0.20', '0.02', null, '1.25') },
  'gpt-5.4-pro': { base: oa('30', null, null, '180'), long: { above: LONG_OA, row: oa('60', null, null, '270') } },
  'gpt-5.2': { base: oa('1.75', '0.175', null, '14') },
  'gpt-5.2-pro': { base: oa('21', null, null, '168') },
  'gpt-5.1': { base: oa('1.25', '0.125', null, '10') },
  'gpt-5': { base: oa('1.25', '0.125', null, '10') },
  'gpt-5-mini': { base: oa('0.25', '0.025', null, '2') },
  'gpt-5-nano': { base: oa('0.05', '0.005', null, '0.40') },
  'gpt-5-pro': { base: oa('15', null, null, '120') },
  'gpt-4.1': { base: oa('2', '0.50', null, '8') },
  'gpt-4.1-mini': { base: oa('0.40', '0.10', null, '1.60') },
  'gpt-4.1-nano': { base: oa('0.10', '0.025', null, '0.40') },
  'gpt-4o': { base: oa('2.50', '1.25', null, '10') },
  'gpt-4o-2024-05-13': { base: oa('5', null, null, '15') },
  'gpt-4o-mini': { base: oa('0.15', '0.075', null, '0.60') },
  o3: { base: oa('2', '0.50', null, '8') },
  'o3-pro': { base: oa('20', null, null, '80') },
  'o3-mini': { base: oa('1.10', '0.55', null, '4.40') },
  'o4-mini': { base: oa('1.10', '0.275', null, '4.40') },
  o1: { base: oa('15', '7.50', null, '60') },
  'o1-pro': { base: oa('150', null, null, '600') },
};

export const PRICES_VERIFIED_ON = '2026-10-10';

export function builtinModels(vendor: Vendor): string[] {
  return Object.keys(vendor === 'anthropic' ? ANTHROPIC : OPENAI);
}

function fromEntry(name: string, e: Entry): ModelPrice {
  return {
    pricedAs: name,
    source: 'builtin',
    base: toTier(e.base),
    longContext: e.long ? { aboveInputTokens: e.long.above, tier: toTier(e.long.row) } : null,
  };
}

/**
 * The built-in price of `model`: an exact id first, then the id without a dated snapshot suffix
 * (`claude-haiku-4-5-20251001` → `claude-haiku-4-5`, `gpt-5-2025-08-07` → `gpt-5`). Null when unknown.
 */
export function builtinPrice(vendor: Vendor, model: string): ModelPrice | null {
  const table = vendor === 'anthropic' ? ANTHROPIC : OPENAI;
  const exact = table[model];
  if (exact) return fromEntry(model, exact);
  const base = model.replace(/-\d{8}$/, '').replace(/-\d{4}-\d{2}-\d{2}$/, '');
  if (base !== model) {
    const e = table[base];
    if (e) return fromEntry(base, e);
  }
  return null;
}

const OVERRIDE_KEYS = ['input', 'output', 'cached_input', 'cache_write', 'cache_write_1h'] as const;

/**
 * Parses an AGENT_PRICE_* value: comma-separated `key=USD-per-million-tokens` pairs, e.g.
 * `input=4,output=20,cached_input=0.20,cache_write=5,cache_write_1h=8`. `input` and `output` are required; a missing
 * `cached_input` defaults to `input`, `cache_write` to 1.25 × input and `cache_write_1h` to 2 × input (the vendors'
 * usual multipliers, never lower than the real price). An override is a flat price (no long-context tier).
 */
export function parsePriceOverride(text: string, label: string): ModelPrice {
  const values = new Map<string, bigint>();
  for (const part of text.split(',')) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf('=');
    if (eq < 0) throw new Error(`${label}: "${trimmed}" is not key=value`);
    const key = trimmed.slice(0, eq).trim().toLowerCase();
    if (!(OVERRIDE_KEYS as readonly string[]).includes(key)) {
      throw new Error(`${label}: unknown key "${key}" (allowed: ${OVERRIDE_KEYS.join(', ')})`);
    }
    try {
      values.set(key, parsePerMillionTokens(trimmed.slice(eq + 1)));
    } catch (e) {
      throw new Error(`${label}: ${key}: ${(e as Error).message}`, { cause: e });
    }
  }
  const input = values.get('input');
  const output = values.get('output');
  if (input === undefined || output === undefined) throw new Error(`${label}: input and output are required`);
  return {
    pricedAs: label,
    source: 'env',
    base: {
      input,
      output,
      cachedInput: values.get('cached_input') ?? input,
      cacheWrite5m: values.get('cache_write') ?? scalePrice(input, 5n, 4n),
      cacheWrite1h: values.get('cache_write_1h') ?? input * 2n,
    },
    longContext: null,
  };
}
