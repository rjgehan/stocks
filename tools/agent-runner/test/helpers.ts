import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { type BotConfig, loadConfig, type RunnerConfig } from '../src/config.js';
import { Logger, type Sink } from '../src/log.js';
import { adapterFor } from '../src/main.js';
import type { ConnectMcp } from '../src/mcp.js';
import type { RunnerDeps } from '../src/runner.js';
import type { Clock } from '../src/time.js';

export const CLAUDE_LLM_KEY = 'sk-ant-api03-FAKE-claude-llm-key-0123456789';
export const OPENAI_LLM_KEY = 'sk-proj-FAKE-openai-llm-key-0123456789abcdef';
export const CLAUDE_STOCKS_KEY = 'stk_claudekey1_FAKEsecretFAKEsecret0123';
export const CHATGPT_STOCKS_KEY = 'stk_chatgptk1_FAKEsecretFAKEsecret4567';

export class MemorySink implements Sink {
  lines: string[] = [];
  write(line: string): void {
    this.lines.push(line);
  }
  get text(): string {
    return this.lines.join('');
  }
}

export function tempDir(): string {
  return mkdtempSync(join(tmpdir(), 'agent-runner-test-'));
}

export function removeDir(dir: string): void {
  rmSync(dir, { recursive: true, force: true });
}

export interface EnvOptions {
  dataDir: string;
  anthropicUrl?: string;
  openaiUrl?: string;
  extra?: Record<string, string>;
}

export function testEnv(o: EnvOptions): Record<string, string> {
  return {
    STOCKS_MCP_URL: 'http://127.0.0.1:9/mcp',
    AGENT_DATA_DIR: o.dataDir,
    ANTHROPIC_API_KEY: CLAUDE_LLM_KEY,
    OPENAI_API_KEY: OPENAI_LLM_KEY,
    STOCKS_CLAUDE_KEY: CLAUDE_STOCKS_KEY,
    STOCKS_CHATGPT_KEY: CHATGPT_STOCKS_KEY,
    CLAUDE_MODEL: 'claude-opus-5-5',
    OPENAI_MODEL: 'gpt-5.5',
    ...(o.anthropicUrl ? { ANTHROPIC_BASE_URL: o.anthropicUrl } : {}),
    ...(o.openaiUrl ? { OPENAI_BASE_URL: `${o.openaiUrl}/v1` } : {}),
    ...o.extra,
  };
}

export function testConfig(o: EnvOptions): RunnerConfig {
  return loadConfig(testEnv(o), 'SYSTEM PROMPT (test)');
}

export const fixedClock = (iso: string): Clock => {
  const t = new Date(iso).getTime();
  const start = Date.now();
  return { now: () => new Date(t + (Date.now() - start)) };
};

export function testDeps(
  config: RunnerConfig,
  connectMcp: ConnectMcp,
  extra: Partial<RunnerDeps> = {},
): RunnerDeps & { sink: MemorySink } {
  const sink = new MemorySink();
  return {
    config,
    logger: new Logger(config.secrets, sink),
    clock: fixedClock('2026-10-12T13:45:00Z'),
    connectMcp,
    adapterFor,
    version: '1.0.0-test',
    shutdown: new AbortController().signal,
    sleep: async () => undefined,
    ...extra,
    sink,
  };
}

export function bot(config: RunnerConfig, id: 'claude' | 'chatgpt'): BotConfig {
  const b = config.bots.find((x) => x.id === id);
  if (!b) throw new Error(`bot ${id} not configured: ${JSON.stringify(config.skipped)}`);
  return b;
}

export interface LogLine {
  type: string;
  [key: string]: unknown;
}

export function readRunLog(path: string | null): LogLine[] {
  if (!path) throw new Error('no run log');
  return readFileSync(path, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l) as LogLine);
}

export function runLogs(dataDir: string, botId: string): string[] {
  try {
    return readdirSync(join(dataDir, botId, 'runs'));
  } catch {
    return [];
  }
}
