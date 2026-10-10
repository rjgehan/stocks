// Exact dollar arithmetic for the cost cap (no floating point): amounts are BigInt pico-dollars (1e-12 USD), prices
// are BigInt pico-dollars per token. $1.00 per million tokens is 1e-6 USD per token = 1_000_000 pico-dollars per token,
// so every published per-MTok price with up to 6 decimals is an exact integer here.

export const PICO_PER_USD = 1_000_000_000_000n;

const DECIMAL = /^(\d{1,9})(?:\.(\d{1,12}))?$/;

/** Parses a non-negative decimal string with up to `scale` decimals into an integer scaled by 10^scale. */
export function parseScaled(text: string, scale: number): bigint {
  const m = DECIMAL.exec(text.trim());
  if (!m) throw new Error(`not a non-negative decimal: "${text}"`);
  const whole = m[1] ?? '0';
  const frac = m[2] ?? '';
  if (frac.length > scale) throw new Error(`"${text}" has more than ${scale} decimals`);
  return BigInt(whole) * 10n ** BigInt(scale) + BigInt(frac.padEnd(scale, '0') || '0');
}

/** "1.00" (USD) → pico-dollars. */
export function parseUsd(text: string): bigint {
  return parseScaled(text, 12);
}

/** "4.00" (USD per million tokens) → pico-dollars per token. */
export function parsePerMillionTokens(text: string): bigint {
  return parseScaled(text, 6);
}

/** Pico-dollars → "0.041234" with `decimals` places, rounded half up. */
export function formatUsd(pico: bigint, decimals = 6): string {
  const negative = pico < 0n;
  let abs = negative ? -pico : pico;
  const drop = 12 - decimals;
  if (drop > 0) {
    const unit = 10n ** BigInt(drop);
    abs = (abs + unit / 2n) / unit;
  }
  const base = 10n ** BigInt(decimals);
  const whole = abs / base;
  const frac = (abs % base).toString().padStart(decimals, '0');
  return `${negative ? '-' : ''}${whole}${decimals > 0 ? `.${frac}` : ''}`;
}

/** Price per token (pico) × `numerator / denominator`, rounded up, e.g. 1.25 × input for a cache write. */
export function scalePrice(price: bigint, numerator: bigint, denominator: bigint): bigint {
  return (price * numerator + denominator - 1n) / denominator;
}
