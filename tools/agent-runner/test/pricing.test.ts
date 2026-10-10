import { describe, expect, it } from 'vitest';
import { formatUsd, parsePerMillionTokens, parseUsd } from '../src/money.js';
import { builtinModels, builtinPrice, costOf, parsePriceOverride, type TokenUsage } from '../src/pricing.js';

const usage = (u: Partial<TokenUsage>): TokenUsage => ({
  input: 0,
  cacheRead: 0,
  cacheWrite5m: 0,
  cacheWrite1h: 0,
  output: 0,
  ...u,
});

describe('money', () => {
  it('parses and formats exactly, without floating point', () => {
    expect(parseUsd('1.00')).toBe(1_000_000_000_000n);
    expect(parseUsd('0.000000000001')).toBe(1n);
    expect(parsePerMillionTokens('0.005')).toBe(5_000n);
    expect(formatUsd(1_234_567_890_123n)).toBe('1.234568');
    expect(formatUsd(1_234_567_890_123n, 2)).toBe('1.23');
    expect(formatUsd(5_000_000_000n, 2)).toBe('0.01');
    expect(formatUsd(4_999_999_999n, 2)).toBe('0.00');
    expect(formatUsd(0n)).toBe('0.000000');
  });

  it('rejects malformed amounts', () => {
    for (const bad of ['', '-1', '1e3', '1,00', 'abc', '0.0000001']) {
      expect(() => parsePerMillionTokens(bad)).toThrow(/decimal/);
    }
  });
});

describe('built-in prices', () => {
  it('prices Claude Opus 5.5 per the pricing page (4 / 0.20 / 5 / 8 / 20 per MTok)', () => {
    const p = builtinPrice('anthropic', 'claude-opus-5-5');
    expect(p?.source).toBe('builtin');
    // 1M of each kind of token costs exactly the per-MTok price.
    const m = 1_000_000;
    expect(formatUsd(costOf(usage({ input: m }), p!))).toBe('4.000000');
    expect(formatUsd(costOf(usage({ cacheRead: m }), p!))).toBe('0.200000');
    expect(formatUsd(costOf(usage({ cacheWrite5m: m }), p!))).toBe('5.000000');
    expect(formatUsd(costOf(usage({ cacheWrite1h: m }), p!))).toBe('8.000000');
    expect(formatUsd(costOf(usage({ output: m }), p!))).toBe('20.000000');
    expect(formatUsd(costOf(usage({ input: 1234, cacheRead: 50_000, output: 777 }), p!))).toBe('0.030476');
  });

  it('applies the Haiku 5.5 long-context price above 100,000 prompt tokens (cache reads count)', () => {
    const p = builtinPrice('anthropic', 'claude-haiku-5-5')!;
    expect(formatUsd(costOf(usage({ input: 100_000, output: 1000 }), p))).toBe('0.010500');
    expect(formatUsd(costOf(usage({ input: 60_000, cacheRead: 40_001, output: 1000 }), p))).toBe('0.034500');
  });

  it('applies the OpenAI long-context price above 272K input tokens', () => {
    const p = builtinPrice('openai', 'gpt-5.5')!;
    expect(formatUsd(costOf(usage({ input: 272_000 }), p))).toBe('1.360000');
    expect(formatUsd(costOf(usage({ input: 272_001 }), p))).toBe('2.720010');
  });

  it('bills OpenAI cache writes at the listed price, or as plain input when none is listed', () => {
    // 100K tokens: under the 272K long-context threshold
    expect(formatUsd(costOf(usage({ cacheWrite5m: 100_000 }), builtinPrice('openai', 'gpt-6-sol')!))).toBe('0.250000');
    expect(formatUsd(costOf(usage({ cacheWrite5m: 100_000 }), builtinPrice('openai', 'gpt-5.4')!))).toBe('0.250000');
    expect(formatUsd(costOf(usage({ cacheRead: 100_000 }), builtinPrice('openai', 'gpt-5-pro')!))).toBe('1.500000');
  });

  it('falls back from a dated snapshot id to its base model, but an exact snapshot entry wins', () => {
    expect(builtinPrice('anthropic', 'claude-haiku-4-5-20251001')?.pricedAs).toBe('claude-haiku-4-5');
    expect(builtinPrice('openai', 'gpt-5-2025-08-07')?.pricedAs).toBe('gpt-5');
    expect(builtinPrice('openai', 'gpt-4o-2024-05-13')?.pricedAs).toBe('gpt-4o-2024-05-13');
  });

  it('knows nothing about unknown or cross-vendor models', () => {
    expect(builtinPrice('anthropic', 'claude-imaginary-9')).toBeNull();
    expect(builtinPrice('anthropic', 'gpt-5.5')).toBeNull();
    expect(builtinPrice('openai', 'claude-opus-5-5')).toBeNull();
  });

  it('has every price as an exact integer number of pico-dollars per token', () => {
    for (const vendor of ['anthropic', 'openai'] as const) {
      for (const model of builtinModels(vendor)) {
        const p = builtinPrice(vendor, model)!;
        expect(p.base.input).toBeGreaterThan(0n);
        expect(p.base.output).toBeGreaterThan(p.base.input - 1n);
        expect(p.base.cachedInput).toBeLessThanOrEqual(p.base.input);
      }
    }
  });
});

describe('AGENT_PRICE_* overrides', () => {
  it('parses key=value pairs and fills conservative defaults', () => {
    const p = parsePriceOverride('input=3, output=15', 'AGENT_PRICE_CLAUDE');
    expect(p.source).toBe('env');
    expect(p.longContext).toBeNull();
    const m = 1_000_000;
    expect(formatUsd(costOf(usage({ cacheRead: m }), p))).toBe('3.000000');
    expect(formatUsd(costOf(usage({ cacheWrite5m: m }), p))).toBe('3.750000');
    expect(formatUsd(costOf(usage({ cacheWrite1h: m }), p))).toBe('6.000000');
    const full = parsePriceOverride('input=4,output=20,cached_input=0.2,cache_write=5,cache_write_1h=8', 'X');
    expect(formatUsd(costOf(usage({ cacheRead: m }), full))).toBe('0.200000');
  });

  it('rejects incomplete or malformed overrides', () => {
    expect(() => parsePriceOverride('input=3', 'AGENT_PRICE_CLAUDE')).toThrow(/input and output are required/);
    expect(() => parsePriceOverride('input=3,output=x', 'AGENT_PRICE_CLAUDE')).toThrow(/output/);
    expect(() => parsePriceOverride('input=3,output=4,foo=1', 'AGENT_PRICE_CLAUDE')).toThrow(/unknown key "foo"/);
    expect(() => parsePriceOverride('3,4', 'AGENT_PRICE_CLAUDE')).toThrow(/key=value/);
  });
});
