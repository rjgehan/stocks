#!/usr/bin/env node
// stocks agent runner (ARCHITECTURE §20.5, docs/agents.md §2.5).
//
//   node dist/main.js                       run on AGENT_SCHEDULE_ET, plus the runs the bots book, until SIGTERM
//   node dist/main.js --once [--bot NAME]   one run now for claude, chatgpt or all (default), then exit
//   node dist/main.js --check               print the configuration (no secrets) and exit
//
// Exit codes for --once: 0 every requested bot ran (or stopped at a cap), 1 a run failed, 2 a requested bot is not
// configured (missing key or model, unknown price).

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { BOT_IDS, type BotConfig, ConfigError, loadConfig, type RunnerConfig } from './config.js';
import { AnthropicAdapter } from './llm/anthropic.js';
import { OpenAIAdapter } from './llm/openai.js';
import type { HttpObserver, ModelAdapter } from './llm/types.js';
import { errorMessage, Logger } from './log.js';
import { connectMcp } from './mcp.js';
import { formatUsd } from './money.js';
import { PRICES_VERIFIED_ON } from './pricing.js';
import { runBot, type RunnerDeps, type RunSummary } from './runner.js';
import { runSchedule } from './scheduler.js';
import { pendingRuns } from './requested.js';
import { botDir, checkWritable } from './store.js';
import { nextSlot, systemClock } from './time.js';

export function runnerVersion(): string {
  try {
    const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { version?: string };
    return pkg.version ?? '0.0.0';
  } catch {
    return '0.0.0';
  }
}

export function adapterFor(bot: BotConfig, onHttpStatus: HttpObserver): ModelAdapter {
  return bot.vendor === 'anthropic'
    ? new AnthropicAdapter({ apiKey: bot.llmApiKey, model: bot.model, baseURL: bot.llmBaseUrl, onHttpStatus })
    : new OpenAIAdapter({ apiKey: bot.llmApiKey, model: bot.model, baseURL: bot.llmBaseUrl, onHttpStatus });
}

interface Args {
  once: boolean;
  check: boolean;
  help: boolean;
  bot: string;
}

export function parseArgs(argv: string[]): Args {
  const args: Args = { once: false, check: false, help: false, bot: 'all' };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--once') args.once = true;
    else if (a === '--check') args.check = true;
    else if (a === '--help' || a === '-h') args.help = true;
    else if (a === '--bot') args.bot = argv[++i] ?? '';
    else if (a?.startsWith('--bot=')) args.bot = a.slice('--bot='.length);
    else throw new ConfigError(`unknown argument "${a}" (try --help)`);
  }
  if (args.bot !== 'all' && !(BOT_IDS as readonly string[]).includes(args.bot)) {
    throw new ConfigError(`--bot must be ${BOT_IDS.join(', ')} or all, not "${args.bot}"`);
  }
  return args;
}

const HELP = `stocks agent runner

  node dist/main.js                       run on AGENT_SCHEDULE_ET (ET) on trading days (every day
                                          while crypto trades), plus the extra runs the bots book
                                          with schedule_run, until SIGTERM
  node dist/main.js --once [--bot NAME]   run claude, chatgpt or all (default) once now, then exit
  node dist/main.js --check               print the configuration (never secrets) and exit

Environment: STOCKS_MCP_URL, ANTHROPIC_API_KEY, OPENAI_API_KEY, STOCKS_CLAUDE_KEY, STOCKS_CHATGPT_KEY,
CLAUDE_MODEL, OPENAI_MODEL, AGENT_SCHEDULE_ET, AGENT_DAILY_USD_CAP, AGENT_MAX_TOOL_CALLS, AGENT_MAX_RUN_SECONDS,
AGENT_EXTRA_RUNS_PER_DAY, AGENT_PRICE_CLAUDE, AGENT_PRICE_CHATGPT, AGENT_DATA_DIR (default /data). See docs/agents.md.
`;

function describe(config: RunnerConfig, logger: Logger, version: string): void {
  logger.info(
    `stocks agent runner ${version}: mcp=${config.mcpUrl} data=${config.dataDir} schedule=${config.scheduleText} ET ` +
      `caps: ${config.maxToolCalls} tool calls, ${config.maxRunSeconds} s per run, $${config.dailyUsdCapText} per bot ` +
      `per day, ${config.extraRunsPerDay} booked extra runs per bot per day (built-in prices checked ${PRICES_VERIFIED_ON})`,
  );
  for (const b of config.bots) {
    const p = b.price.base;
    logger.info(
      `bot enabled: ${b.vendor} model ${b.model}, priced as ${b.price.pricedAs} (${b.price.source}): ` +
        `$${formatUsd(p.input * 1_000_000n, 4)} in / $${formatUsd(p.cachedInput * 1_000_000n, 4)} cached / ` +
        `$${formatUsd(p.output * 1_000_000n, 4)} out per 1M tokens` +
        (b.price.longContext ? ` (higher above ${b.price.longContext.aboveInputTokens} prompt tokens)` : ''),
      b.id,
    );
  }
  for (const s of config.skipped) logger.warn(`bot skipped: ${s.reason}`, s.id);
}

export async function main(argv: string[], env: NodeJS.ProcessEnv): Promise<number> {
  const bootLogger = new Logger([]);
  let args: Args;
  let config: RunnerConfig;
  try {
    args = parseArgs(argv);
    if (args.help) {
      process.stdout.write(HELP);
      return 0;
    }
    config = loadConfig(env);
  } catch (e) {
    bootLogger.error(`configuration error: ${errorMessage(e)}`);
    return 1;
  }
  const logger = new Logger(config.secrets);
  const version = runnerVersion();
  describe(config, logger, version);
  if (args.check) {
    if (config.bots.length > 0) {
      const slot = nextSlot(systemClock.now(), config.schedule);
      logger.info(`next scheduled slot: ${slot.dateEt} ${slot.label} ET`);
    }
    for (const b of config.bots) {
      try {
        const booked = pendingRuns(botDir(config.dataDir, b.id));
        logger.info(
          `booked extra runs: ${booked.length === 0 ? 'none' : booked.map((r) => `${r.id} at ${r.atEt} ET`).join(', ')}`,
          b.id,
        );
      } catch (e) {
        logger.warn(`cannot read the booked runs: ${errorMessage(e)}`, b.id);
      }
    }
    return 0;
  }

  try {
    checkWritable(config.dataDir);
  } catch (e) {
    logger.error(
      `cannot write to ${config.dataDir} (${errorMessage(e)}). The image runs as uid 1000: on the server run ` +
        `"sudo chown -R 1000:1000 /DATA/AppData/stocks/agent-data" (docs/deploy.md).`,
    );
    return 1;
  }

  const shutdownController = new AbortController();
  let signals = 0;
  const onSignal = (sig: NodeJS.Signals): void => {
    signals++;
    if (signals > 1) {
      logger.warn(`${sig} again: exiting now`);
      process.exit(130);
    }
    logger.info(`${sig}: stopping (in-flight model requests are cancelled; a running tool call may finish)`);
    shutdownController.abort(new Error(sig));
  };
  process.on('SIGTERM', onSignal);
  process.on('SIGINT', onSignal);
  try {
    return await serve(args, config, logger, version, shutdownController);
  } finally {
    process.off('SIGTERM', onSignal);
    process.off('SIGINT', onSignal);
  }
}

async function serve(
  args: Args,
  config: RunnerConfig,
  logger: Logger,
  version: string,
  shutdownController: AbortController,
): Promise<number> {
  const deps: RunnerDeps = {
    config,
    logger,
    clock: systemClock,
    connectMcp,
    adapterFor,
    version,
    shutdown: shutdownController.signal,
  };

  if (args.once) {
    const wanted = args.bot === 'all' ? [...BOT_IDS] : [args.bot];
    const bots = config.bots.filter((b) => wanted.includes(b.id));
    const missing = config.skipped.filter((s) => wanted.includes(s.id));
    const results: RunSummary[] = await Promise.all(
      bots.map((b) => runBot(b, deps, { trigger: 'once', slot: null, requireTradingDay: false })),
    );
    if (results.some((r) => r.status === 'error' || r.status === 'skipped_busy')) return 1;
    if (missing.length > 0 || bots.length === 0) return 2;
    return 0;
  }

  if (config.bots.length === 0) {
    logger.warn('no bot is configured, so nothing will run; set the keys and models in .env (docs/agents.md §2.5)');
    // Stay up (a container that exits would be restarted in a loop) until SIGTERM. A pending promise alone does not
    // keep Node running, so hold a timer.
    const keepAlive = setInterval(() => undefined, 3_600_000);
    await new Promise<void>((resolve) => shutdownController.signal.addEventListener('abort', () => resolve()));
    clearInterval(keepAlive);
    return 0;
  }
  await runSchedule(config.bots, deps);
  logger.info('stopped');
  return 0;
}

const isEntry = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isEntry) {
  main(process.argv.slice(2), process.env).then(
    (code) => process.exit(code),
    (e: unknown) => {
      new Logger([]).error(`fatal: ${errorMessage(e)}`);
      process.exit(1);
    },
  );
}
