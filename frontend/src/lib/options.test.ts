import { describe, expect, it } from 'vitest'
import {
  MULTIPLIER,
  contractsLabel,
  expiryLabel,
  formatOptionPrice,
  isExpiryOrder,
  daysToExpiry,
  formatOption,
  inTheMoney,
  intrinsicValue,
  isOption,
  optionLabel,
  optionShortLabel,
  parseOption,
  underlyingOf,
} from './options'

describe('options (OCC symbols)', () => {
  it('recognises OCC symbols only', () => {
    expect(isOption('AAPL261120C00250000')).toBe(true)
    expect(isOption('F261218P00012500')).toBe(true)
    expect(isOption('GOOGLL261120C00250000')).toBe(true)
    expect(isOption('aapl261120c00250000')).toBe(false)
    expect(isOption('AAPL')).toBe(false)
    expect(isOption('BTC/USD')).toBe(false)
    expect(isOption('AAPL261120X00250000')).toBe(false)
    expect(isOption('AAPL261120C0025000')).toBe(false)
    expect(isOption('GOOGLLL261120C00250000')).toBe(false)
    expect(isOption(null)).toBe(false)
  })

  it('parses and formats round trip', () => {
    expect(parseOption('AAPL261120C00250000')).toEqual({ underlying: 'AAPL', expiration: '2026-11-20', right: 'CALL', strike: '250' })
    expect(parseOption('F261218P00012500')).toEqual({ underlying: 'F', expiration: '2026-12-18', right: 'PUT', strike: '12.5' })
    expect(parseOption('SPY270115C00682125')?.strike).toBe('682.125')
    expect(parseOption('AAPL261320C00250000')).toBeNull()
    expect(parseOption('AAPL')).toBeNull()
    for (const s of ['AAPL261120C00250000', 'F261218P00012500', 'SPY270115C00682125', 'BRK261120P00001000']) expect(formatOption(parseOption(s)!)).toBe(s)
    expect(formatOption({ underlying: 'nvda', expiration: '2027-01-15', right: 'PUT', strike: '135.5' })).toBe('NVDA270115P00135500')
    expect(underlyingOf('AAPL261120C00250000')).toBe('AAPL')
    expect(underlyingOf('MSFT')).toBe('MSFT')
  })

  it('labels a contract for people', () => {
    expect(optionLabel('AAPL261120C00250000')).toBe('AAPL 20 Nov 26 $250 Call')
    expect(optionLabel('F270105P00012500')).toBe('F 5 Jan 27 $12.5 Put')
    expect(optionShortLabel('AAPL261120C00250000')).toBe('AAPL 250C 11/20')
    expect(optionShortLabel('F270105P00012500')).toBe('F 12.5P 01/05')
    expect(optionLabel('NVDA')).toBe('NVDA')
    expect(optionShortLabel(null)).toBe('')
  })

  it('values the intrinsic part per share; a contract is 100 shares', () => {
    expect(MULTIPLIER).toBe(100)
    expect(intrinsicValue('CALL', '250', '262.40')).toBe('12.40')
    expect(intrinsicValue('CALL', '250', '240')).toBe('0')
    expect(intrinsicValue('PUT', '250', '240.25')).toBe('9.75')
    expect(intrinsicValue('PUT', '250', '250.00')).toBe('0')
    expect(inTheMoney('CALL', '250', '250.01')).toBe(true)
    expect(inTheMoney('CALL', '250', '250.00')).toBe(false)
    expect(inTheMoney('PUT', '250', '249.99')).toBe(true)
    expect(inTheMoney('PUT', '250', null)).toBe(false)
  })

  it('labels contracts, cent prices and expiry settlements', () => {
    expect(contractsLabel('1')).toBe('1 contract')
    expect(contractsLabel('3')).toBe('3 contracts')
    expect(formatOptionPrice('0.4500')).toBe('0.45')
    expect(expiryLabel('0.6200')).toBe('Cash-settled at $0.62')
    expect(expiryLabel('0.0000')).toBe('Expired worthless')
    expect(isExpiryOrder({ symbol: 'F261016C00011000', side: 'SELL', clientOrderId: 'expiry:F261016C00011000' })).toBe(true)
    expect(isExpiryOrder({ symbol: 'F261016C00011000', side: 'SELL', clientOrderId: 'web-1', channel: 'WEB' })).toBe(false)
    expect(isExpiryOrder({ symbol: 'F261016C00011000', side: 'SELL', channel: 'SYSTEM' })).toBe(true)
    expect(isExpiryOrder({ symbol: 'F', side: 'SELL', channel: 'SYSTEM' })).toBe(false)
  })

  it('counts calendar days to expiration', () => {
    expect(daysToExpiry('2026-11-20', '2026-10-23')).toBe(28)
    expect(daysToExpiry('2026-10-23', '2026-10-23')).toBe(0)
  })
})
