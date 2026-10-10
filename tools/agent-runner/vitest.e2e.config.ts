import { defineConfig } from 'vitest/config';

// End-to-end: the real stocks backend (dev profile, SIM) + the real MCP server, fake LLM APIs. See test/e2e/README.
export default defineConfig({
  test: {
    include: ['test/e2e/**/*.test.ts'],
    environment: 'node',
    testTimeout: 240_000,
    hookTimeout: 240_000,
    fileParallelism: false,
  },
});
