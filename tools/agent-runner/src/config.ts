// Runner configuration from the environment (ARCHITECTURE §20.5, §23.3; docs/agents.md §2.5).

import { readFileSync } from 'node:fs';
import { parseUsd } from './money.js';
import { builtinPrice, parsePriceOverride, type ModelPrice, type Vendor } from './pricing.js';
import { reportUrl } from './report.js';
import { parseSchedule, type SlotTime } from './time.js';

export type BotId = 'claude' | 'chatgpt';
export const BOT_IDS: readonly BotId[] = ['claude', 'chatgpt'];

export interface BotConfig {
  id: BotId;
  vendor: Vendor;
  model: string;
  llmApiKey: string;
  stocksKey: string;
  /** Override of the vendor API base URL (tests point it at a local fake); undefined = the SDK default. */
  llmBaseUrl: string | undefined;
  price: ModelPrice;
}

export interface BotSkip {
  id: BotId;
  reason: string;
}

export interface RunnerConfig {
  mcpUrl: string;
  dataDir: string;
  schedule: SlotTime[];
  scheduleText: string;
  maxToolCalls: number;
  maxRunSeconds: number;
  /** Extra runs a bot may book for itself per ET day (schedule_run); 0 turns the booking tools off. */
  extraRunsPerDay: number;
  /** Pico-dollars. */
  dailyUsdCap: bigint;
  dailyUsdCapText: string;
  systemPrompt: string;
  /** Where run reports go (§20.6); null when AGENT_REPORT_RUNS=false. */
  reportUrl: string | null;
  bots: BotConfig[];
  skipped: BotSkip[];
  /** Every secret value, for log redaction. */
  secrets: string[];
}

/** Same for both bots (fairness, §20.5). */
export const MAX_OUTPUT_TOKENS = 16_000;
export const NOTES_MAX_BYTES = 8192;
export const LLM_MAX_RETRIES = 4;
export const MCP_CALL_TIMEOUT_MS = 30_000;
export const TOOL_RESULT_MAX_CHARS = 40_000;

interface BotEnv {
  id: BotId;
  vendor: Vendor;
  llmKeyVar: string;
  stocksKeyVar: string;
  modelVar: string;
  priceVar: string;
  baseUrlVar: string;
}

const BOT_ENV: Record<BotId, BotEnv> = {
  claude: {
    id: 'claude',
    vendor: 'anthropic',
    llmKeyVar: 'ANTHROPIC_API_KEY',
    stocksKeyVar: 'STOCKS_CLAUDE_KEY',
    modelVar: 'CLAUDE_MODEL',
    priceVar: 'AGENT_PRICE_CLAUDE',
    baseUrlVar: 'ANTHROPIC_BASE_URL',
  },
  chatgpt: {
    id: 'chatgpt',
    vendor: 'openai',
    llmKeyVar: 'OPENAI_API_KEY',
    stocksKeyVar: 'STOCKS_CHATGPT_KEY',
    modelVar: 'OPENAI_MODEL',
    priceVar: 'AGENT_PRICE_CHATGPT',
    baseUrlVar: 'OPENAI_BASE_URL',
  },
};

export class ConfigError extends Error {}

type Env = Record<string, string | undefined>;

function str(env: Env, name: string): string {
  return (env[name] ?? '').trim();
}

function int(env: Env, name: string, fallback: number, min: number, max: number): number {
  const raw = str(env, name);
  if (!raw) return fallback;
  if (!/^\d+$/.test(raw)) throw new ConfigError(`${name}="${raw}" is not a whole number`);
  const n = Number(raw);
  if (n < min || n > max) throw new ConfigError(`${name}=${n} must be between ${min} and ${max}`);
  return n;
}

/** The open, the middle of the regular session and ten minutes before the close (ET). */
export const DEFAULT_SCHEDULE_ET = '09:30,12:45,15:50';

export const DEFAULT_SYSTEM_PROMPT_PATH = new URL('../prompts/system.md', import.meta.url);

export function loadConfig(env: Env, systemPrompt?: string): RunnerConfig {
  const scheduleText = str(env, 'AGENT_SCHEDULE_ET') || DEFAULT_SCHEDULE_ET;
  let schedule: SlotTime[];
  try {
    schedule = parseSchedule(scheduleText);
  } catch (e) {
    throw new ConfigError((e as Error).message);
  }
  const dailyUsdCapText = str(env, 'AGENT_DAILY_USD_CAP') || '1.00';
  let dailyUsdCap: bigint;
  try {
    dailyUsdCap = parseUsd(dailyUsdCapText);
  } catch (e) {
    throw new ConfigError(`AGENT_DAILY_USD_CAP: ${(e as Error).message}`);
  }
  const mcpUrl = str(env, 'STOCKS_MCP_URL') || 'http://app:8080/mcp';
  try {
    const u = new URL(mcpUrl);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new Error('not http(s)');
  } catch {
    throw new ConfigError(`STOCKS_MCP_URL="${mcpUrl}" is not an http(s) URL`);
  }

  const reportRuns = (str(env, 'AGENT_REPORT_RUNS') || 'true').toLowerCase();
  if (reportRuns !== 'true' && reportRuns !== 'false') {
    throw new ConfigError(`AGENT_REPORT_RUNS="${reportRuns}" must be true or false`);
  }
  let runReportUrl: string | null = null;
  if (reportRuns === 'true') {
    try {
      runReportUrl = reportUrl(mcpUrl, str(env, 'AGENT_REPORT_URL') || undefined);
      const u = new URL(runReportUrl);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new Error('not http(s)');
    } catch {
      throw new ConfigError(`AGENT_REPORT_URL="${str(env, 'AGENT_REPORT_URL')}" is not an http(s) URL`);
    }
  }

  const bots: BotConfig[] = [];
  const skipped: BotSkip[] = [];
  const secrets: string[] = [];
  for (const id of BOT_IDS) {
    const e = BOT_ENV[id];
    const llmApiKey = str(env, e.llmKeyVar);
    const stocksKey = str(env, e.stocksKeyVar);
    const model = str(env, e.modelVar);
    if (llmApiKey) secrets.push(llmApiKey);
    if (stocksKey) secrets.push(stocksKey);
    const missing = [
      llmApiKey ? null : e.llmKeyVar,
      stocksKey ? null : e.stocksKeyVar,
      model ? null : e.modelVar,
    ].filter((v): v is string => v !== null);
    if (missing.length > 0) {
      skipped.push({ id, reason: `${missing.join(', ')} ${missing.length === 1 ? 'is' : 'are'} not set` });
      continue;
    }
    const override = str(env, e.priceVar);
    let price: ModelPrice | null;
    try {
      price = override ? parsePriceOverride(override, e.priceVar) : builtinPrice(e.vendor, model);
    } catch (err) {
      throw new ConfigError((err as Error).message);
    }
    if (!price) {
      skipped.push({
        id,
        reason:
          `no price for ${e.modelVar}=${model}, so AGENT_DAILY_USD_CAP cannot be enforced; set ` +
          `${e.priceVar}=input=<USD per 1M tokens>,output=<USD per 1M tokens>[,cached_input=…,cache_write=…]`,
      });
      continue;
    }
    bots.push({
      id,
      vendor: e.vendor,
      model,
      llmApiKey,
      stocksKey,
      llmBaseUrl: str(env, e.baseUrlVar) || undefined,
      price,
    });
  }

  return {
    mcpUrl,
    dataDir: str(env, 'AGENT_DATA_DIR') || '/data',
    schedule,
    scheduleText: schedule.map((s) => s.label).join(','),
    maxToolCalls: int(env, 'AGENT_MAX_TOOL_CALLS', 40, 1, 1000),
    maxRunSeconds: int(env, 'AGENT_MAX_RUN_SECONDS', 300, 10, 3600),
    extraRunsPerDay: int(env, 'AGENT_EXTRA_RUNS_PER_DAY', 4, 0, 20),
    dailyUsdCap,
    dailyUsdCapText,
    systemPrompt: systemPrompt ?? readFileSync(DEFAULT_SYSTEM_PROMPT_PATH, 'utf8').trim(),
    reportUrl: runReportUrl,
    bots,
    skipped,
    secrets,
  };
}
