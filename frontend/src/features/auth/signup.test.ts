import { describe, expect, it } from 'vitest'
import { validateSignup } from './SignupForm'

const ok = { inviteCode: 'welcome-friends', handle: 'alice_1', displayName: 'Alice', password: 'correct horse', confirm: 'correct horse' }

describe('validateSignup (mirrors §8 handle and §17.3 password rules; the server decides)', () => {
  it('accepts a valid form', () => {
    expect(validateSignup(ok)).toEqual({})
  })

  it('checks every field', () => {
    expect(validateSignup({ ...ok, inviteCode: ' ' }).inviteCode).toBeTruthy()
    expect(validateSignup({ ...ok, handle: 'Alice' }).handle).toBeTruthy()
    expect(validateSignup({ ...ok, handle: 'a' }).handle).toBeTruthy()
    expect(validateSignup({ ...ok, displayName: '  ' }).displayName).toBeTruthy()
    expect(validateSignup({ ...ok, password: 'short', confirm: 'short' }).password).toBe('At least 10 characters.')
    expect(validateSignup({ ...ok, password: 'é'.repeat(40), confirm: 'é'.repeat(40) }).password).toBe('At most 72 bytes.')
    expect(validateSignup({ ...ok, confirm: 'different!!' }).confirm).toBeTruthy()
  })
})
