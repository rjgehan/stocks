import { defineConfig } from '@playwright/test'

/**
 * Playwright e2e (§26): viewports 390×844, 768×1024 and 1440×900.
 *  - CI / real backend: E2E_BASE_URL=http://localhost:8080 (the jar serving the built SPA, profiles test,e2e).
 *  - Local default: Vite in mock mode (§22.9) on port E2E_MOCK_PORT (default 5174), started automatically.
 *  - E2E_CHANNEL=chrome uses the installed Google Chrome instead of Playwright's Chromium download.
 * Against a real backend, the test tagged @run-all-backend (Run all, §20.9) asks for every bot a trader served lately,
 * the Run now bots of the viewport projects included, so it runs alone in the `run-all` project, after them (their
 * whole run, whatever files the command line names; `--project=run-all --no-deps` runs it alone).
 */
const external = process.env.E2E_BASE_URL
const mockPort = Number(process.env.E2E_MOCK_PORT ?? 5174)
const baseURL = external ?? `http://localhost:${mockPort}`
const channel = process.env.E2E_CHANNEL
const RUN_ALL_BACKEND = /@run-all-backend/
const desktop = { browserName: 'chromium' as const, viewport: { width: 1440, height: 900 } }

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
    { name: 'phone', grepInvert: RUN_ALL_BACKEND, use: { browserName: 'chromium', viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true } },
    { name: 'tablet', grepInvert: RUN_ALL_BACKEND, use: { browserName: 'chromium', viewport: { width: 768, height: 1024 }, deviceScaleFactor: 2, hasTouch: true } },
    { name: 'desktop', grepInvert: RUN_ALL_BACKEND, use: desktop },
    ...(external ? [{ name: 'run-all', grep: RUN_ALL_BACKEND, dependencies: ['phone', 'tablet', 'desktop'], use: desktop }] : []),
  ],
  webServer: external
    ? undefined
    : {
        command: `npm run dev:mock -- --port ${mockPort} --strictPort`,
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
})
