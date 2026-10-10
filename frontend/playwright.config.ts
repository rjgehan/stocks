import { defineConfig } from '@playwright/test'

/**
 * Playwright e2e (§26): viewports 390×844, 768×1024 and 1440×900.
 *  - CI / real backend: E2E_BASE_URL=http://localhost:8080 (the jar serving the built SPA, profiles test,e2e).
 *  - Local default: Vite in mock mode (§22.9) on port 5174, started automatically.
 *  - E2E_CHANNEL=chrome uses the installed Google Chrome instead of Playwright's Chromium download.
 */
const external = process.env.E2E_BASE_URL
const baseURL = external ?? 'http://localhost:5174'
const channel = process.env.E2E_CHANNEL

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    ...(channel ? { channel } : {}),
  },
  projects: [
    { name: 'phone', use: { browserName: 'chromium', viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true } },
    { name: 'tablet', use: { browserName: 'chromium', viewport: { width: 768, height: 1024 }, deviceScaleFactor: 2, hasTouch: true } },
    { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1440, height: 900 } } },
  ],
  webServer: external
    ? undefined
    : {
        command: 'npm run dev:mock -- --port 5174',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
})
