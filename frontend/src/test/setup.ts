import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// jsdom has no layout: the router's scroll restoration calls window.scrollTo, which jsdom only logs as unimplemented.
window.scrollTo = () => {}

afterEach(() => {
  cleanup()
  document.cookie.split(';').forEach((c) => {
    const name = c.split('=')[0]?.trim()
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  })
})
