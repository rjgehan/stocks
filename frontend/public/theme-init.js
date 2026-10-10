// Applies the saved theme before first paint (an external file because the CSP forbids inline scripts, §24.3).
// Mirrors src/lib/theme/theme.ts. Dark is the default (§22.8).
;(function () {
  var root = document.documentElement
  var theme = 'DARK'
  var upDown = 'GREEN_UP'
  try {
    theme = localStorage.getItem('stocks.theme') || theme
    upDown = localStorage.getItem('stocks.updown') || upDown
  } catch {
    /* storage unavailable: keep defaults */
  }
  if (theme === 'LIGHT') root.setAttribute('data-theme', 'light')
  else if (theme === 'SYSTEM') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', 'dark')
  if (upDown === 'RED_UP') root.setAttribute('data-updown', 'red-up')
})()
