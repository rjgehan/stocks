import { defineConfig } from 'vitest/config';

// Unit and scripted-run tests: fake MCP connection + local fake Anthropic/OpenAI HTTP servers. Never the real APIs.
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    exclude: ['test/e2e/**'],
    environment: 'node',
    testTimeout: 20_000,
  },
});
