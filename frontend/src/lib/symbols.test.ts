import { describe, expect, it } from 'vitest'
import { CRYPTO_PAIRS, baseAsset, fromPath, isCrypto, isSymbolLike, toPath } from './symbols'

describe('symbols', () => {
  it('recognises crypto pairs in either spelling', () => {
    expect(isCrypto('BTC/USD')).toBe(true)
    expect(isCrypto('ETH-USD')).toBe(true)
    expect(isCrypto('doge/usd')).toBe(true)
    expect(isCrypto('AAPL')).toBe(false)
    expect(isCrypto('BRK.B')).toBe(false)
    expect(isCrypto('')).toBe(false)
    expect(isCrypto(null)).toBe(false)
    expect(CRYPTO_PAIRS.every(isCrypto)).toBe(true)
  })

  it('maps crypto pairs to the dash spelling for paths and topics; stocks unchanged', () => {
    expect(toPath('BTC/USD')).toBe('BTC-USD')
    expect(toPath('AAVE/USD')).toBe('AAVE-USD')
    expect(toPath('BTC-USD')).toBe('BTC-USD')
    expect(toPath('AAPL')).toBe('AAPL')
    expect(toPath('BRK.B')).toBe('BRK.B')
  })

  it('maps a path segment back to the canonical upper-case symbol', () => {
    expect(fromPath('BTC-USD')).toBe('BTC/USD')
    expect(fromPath('btc-usd')).toBe('BTC/USD')
    expect(fromPath('BTC/USD')).toBe('BTC/USD')
    expect(fromPath('aapl')).toBe('AAPL')
    expect(fromPath('BRK.B')).toBe('BRK.B')
    expect(CRYPTO_PAIRS.every((p) => fromPath(toPath(p)) === p)).toBe(true)
  })

  it('names the base asset of a pair', () => {
    expect(baseAsset('BTC/USD')).toBe('BTC')
    expect(baseAsset('LINK-USD')).toBe('LINK')
    expect(baseAsset('AAPL')).toBe('AAPL')
  })

  it('accepts tickers and pairs as symbol-like', () => {
    expect(isSymbolLike('AAPL')).toBe(true)
    expect(isSymbolLike('BRK.B')).toBe(true)
    expect(isSymbolLike('BTC/USD')).toBe(true)
    expect(isSymbolLike('BTC-USD')).toBe(true)
    expect(isSymbolLike('aapl')).toBe(false)
    expect(isSymbolLike('<script>')).toBe(false)
  })
})
