import { describe, expect, it } from 'vitest';
import { ConfigError, loadConfig } from '../src/config.js';
import { Logger } from '../src/log.js';
import { main, parseArgs } from '../src/main.js';
import { CLAUDE_LLM_KEY, CLAUDE_STOCKS_KEY, MemorySink, OPENAI_LLM_KEY, removeDir, tempDir, testEnv } from './helpers.js';

describe('configuration', () => {
  it('uses the §20.5 defaults', () => {
    const c = loadConfig({}, 'p');
    expect(c).toMatchObject({
      mcpUrl: 'http://app:8080/mcp',
      dataDir: '/data',
      scheduleText: '09:45,12:30,15:30',
      maxToolCalls: 40,
      maxRunSeconds: 300,
      dailyUsdCap: 1_000_000_000_000n,
      dailyUsdCapText: '1.00',
      bots: [],
    });
  });

  it('skips a bot whose LLM key, stocks key or model is unset, naming only the variables', () => {
    const c = loadConfig({ ANTHROPIC_API_KEY: CLAUDE_LLM_KEY, OPENAI_MODEL: 'gpt-5.5' }, 'p');
    expect(c.bots).toEqual([]);
    expect(c.skipped).toEqual([
      { id: 'claude', reason: 'STOCKS_CLAUDE_KEY, CLAUDE_MODEL are not set' },
      { id: 'chatgpt', reason: 'OPENAI_API_KEY, STOCKS_CHATGPT_KEY are not set' },
    ]);
    expect(JSON.stringify(c.skipped)).not.toContain(CLAUDE_LLM_KEY);
    expect(c.secrets).toEqual([CLAUDE_LLM_KEY]);
  });

  it('refuses to run a bot whose model has no price (the daily cap could not be enforced)', () => {
    const c = loadConfig(testEnv({ dataDir: '/tmp/x', extra: { CLAUDE_MODEL: 'claude-future-9' } }), 'p');
    expect(c.bots.map((b) => b.id)).toEqual(['chatgpt']);
    expect(c.skipped[0]?.reason).toMatch(/no price for CLAUDE_MODEL=claude-future-9.*AGENT_PRICE_CLAUDE=input=/);
  });

  it('an AGENT_PRICE_* override prices an unknown model', () => {
    const c = loadConfig(
      testEnv({ dataDir: '/tmp/x', extra: { CLAUDE_MODEL: 'claude-future-9', AGENT_PRICE_CLAUDE: 'input=6,output=30' } }),
      'p',
    );
    expect(c.bots.find((b) => b.id === 'claude')?.price).toMatchObject({ source: 'env', pricedAs: 'AGENT_PRICE_CLAUDE' });
  });

  it('rejects malformed values with the variable name', () => {
    const bad: Record<string, string>[] = [
      { AGENT_SCHEDULE_ET: '9am' },
      { AGENT_DAILY_USD_CAP: '-1' },
      { AGENT_MAX_TOOL_CALLS: '0' },
      { AGENT_MAX_RUN_SECONDS: 'ten' },
      { STOCKS_MCP_URL: 'ftp://x' },
      { ...testEnv({ dataDir: '/tmp/x' }), AGENT_PRICE_CHATGPT: 'input=1' },
    ];
    for (const env of bad) expect(() => loadConfig(env, 'p')).toThrow(ConfigError);
  });

  it('loads the shared system prompt from prompts/system.md by default', () => {
    expect(loadConfig({}).systemPrompt).toMatch(/^You are an AI player in "stocks"/);
  });
});

describe('command line', () => {
  it('parses --once, --bot and --check', () => {
    expect(parseArgs(['--once', '--bot', 'claude'])).toMatchObject({ once: true, bot: 'claude' });
    expect(parseArgs(['--once', '--bot=chatgpt'])).toMatchObject({ once: true, bot: 'chatgpt' });
    expect(parseArgs([])).toMatchObject({ once: false, check: false, bot: 'all' });
    expect(() => parseArgs(['--bot', 'gemini'])).toThrow(/--bot/);
    expect(() => parseArgs(['--fast'])).toThrow(/unknown argument/);
  });

  it('--once for an unconfigured bot exits 2 without running anything', async () => {
    const dir = tempDir();
    try {
      const code = await main(['--once', '--bot', 'claude'], { AGENT_DATA_DIR: dir, OPENAI_API_KEY: OPENAI_LLM_KEY });
      expect(code).toBe(2);
    } finally {
      removeDir(dir);
    }
  });

  it('--check prints the configuration without secrets', async () => {
    const sink = new MemorySink();
    const write = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((chunk: string) => {
      sink.write(String(chunk));
      return true;
    }) as typeof process.stdout.write;
    try {
      expect(await main(['--check'], testEnv({ dataDir: '/tmp/x' }))).toBe(0);
    } finally {
      process.stdout.write = write;
    }
    expect(sink.text).toContain('[claude] bot enabled: anthropic model claude-opus-5-5, priced as claude-opus-5-5');
    expect(sink.text).toContain('$4.0000 in / $0.2000 cached / $20.0000 out per 1M tokens');
    expect(sink.text).toContain('[chatgpt] bot enabled: openai model gpt-5.5');
    expect(sink.text).not.toContain(CLAUDE_LLM_KEY);
    expect(sink.text).not.toContain(CLAUDE_STOCKS_KEY);
  });
});

describe('log redaction', () => {
  it('removes configured secrets and key-shaped strings', () => {
    const sink = new MemorySink();
    const log = new Logger(['my-very-secret-value'], sink, () => new Date('2026-10-12T13:45:00Z'));
    log.info('a my-very-secret-value b stk_abc123_def456 c sk-ant-api03-xyz123 d sk-proj-0123456789abcdefghij', 'claude');
    log.warn('{"Authorization":"Bearer stk_x"} x-api-key: abcdef');
    expect(sink.lines[0]).toBe('2026-10-12T13:45:00.000Z INFO  [claude] a [redacted] b [redacted] c [redacted] d [redacted]\n');
    expect(sink.lines[1]).not.toContain('stk_x');
    expect(sink.lines[1]).not.toContain('abcdef');
  });
});
