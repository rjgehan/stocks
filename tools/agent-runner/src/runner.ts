// One bot run (ARCHITECTURE §20.5): connect to the stocks MCP server with the bot's key, check the market clock, then
// let the model propose tool calls that the runner executes, within the per-run caps and the per-bot daily dollar cap.

import { createHash } from 'node:crypto';
import type { BotConfig, RunnerConfig } from './config.js';
import { NOTES_MAX_BYTES, TOOL_RESULT_MAX_CHARS } from './config.js';
import type { HttpObserver, ModelAdapter, ModelTurn, ToolCall, ToolResult, TurnInput } from './llm/types.js';
import { errorMessage, type Logger } from './log.js';
import { type ConnectMcp, type McpConnection, runnerError, type ToolOutcome } from './mcp.js';
import { formatUsd } from './money.js';
import { addUsage, costOf, type TokenUsage, ZERO_USAGE } from './pricing.js';
import { RunLog } from './runlog.js';
import { botDir, readNotes, readSpend, saveNotes, type Spend, writeSpend } from './store.js';
import { type Clock, etDate, etTime } from './time.js';
import { functionTools, type JsonObject, SAVE_NOTES } from './tools.js';

export type RunStatus =
  | 'completed'
  | 'tool_cap'
  | 'time_cap'
  | 'daily_cap'
  | 'max_tokens'
  | 'refusal'
  | 'error'
  | 'aborted'
  | 'skipped_daily_cap'
  | 'skipped_non_trading_day';

export interface RunSummary {
  bot: string;
  model: string;
  status: RunStatus;
  turns: number;
  toolCalls: number;
  toolErrors: number;
  orders: number;
  usage: TokenUsage;
  /** Pico-dollars. */
  cost: bigint;
  /** Pico-dollars spent by this bot today (ET), this run included. */
  day: bigint;
  durationMs: number;
  logPath: string | null;
  message: string | null;
}

export interface RunnerDeps {
  config: RunnerConfig;
  logger: Logger;
  clock: Clock;
  connectMcp: ConnectMcp;
  adapterFor: (bot: BotConfig, onHttpStatus: HttpObserver) => ModelAdapter;
  version: string;
  /** Aborted on SIGTERM: in-flight model requests are cancelled and the run ends as `aborted`. */
  shutdown: AbortSignal;
  /** Test hook: the run's time cap in ms (default AGENT_MAX_RUN_SECONDS). */
  runTimeoutMs?: number;
  /** Test hook. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

export interface RunOptions {
  trigger: 'schedule' | 'once';
  /** The schedule slot ("09:45"), null for a manual run. */
  slot: string | null;
  /** Scheduled runs skip a non-trading day; a manual run always runs. */
  requireTradingDay: boolean;
}

const ORDER_TOOLS = new Set(['place_order', 'replace_order', 'close_position']);
const RATE_LIMIT_MAX_WAIT_S = 15;

export function sha256(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function defaultSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason as Error);
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = (): void => {
      clearTimeout(timer);
      reject(signal.reason as Error);
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

function clip(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}…[+${text.length - max} chars]`;
}

/** Tool arguments for the run log: long strings clipped (rationales stay readable, notes stay short). */
function loggableArgs(args: JsonObject | null, name: string): unknown {
  if (!args) return null;
  const out: JsonObject = {};
  for (const [k, v] of Object.entries(args)) {
    if (name === SAVE_NOTES && k === 'text' && typeof v === 'string') out[k] = clip(v, 300);
    else out[k] = typeof v === 'string' ? clip(v, 1000) : v;
  }
  return out;
}

function clipResult(text: string): string {
  if (text.length <= TOOL_RESULT_MAX_CHARS) return text;
  return (
    `${text.slice(0, TOOL_RESULT_MAX_CHARS)}\n…[the agent runner cut this result at ${TOOL_RESULT_MAX_CHARS} of ` +
    `${text.length} characters; ask for less data, e.g. a smaller limit or fewer symbols]`
  );
}

export function buildUserMessage(p: {
  bot: string;
  now: Date;
  slot: string | null;
  scheduleText: string;
  maxToolCalls: number;
  maxRunSeconds: number;
  clockJson: string;
  notes: { text: string; bytes: number; clipped: boolean };
}): string {
  const when = `${etDate(p.now)} ${etTime(p.now)} ET`;
  const run = p.slot ? `scheduled run (the ${p.slot} ET slot), ${when}` : `manual run, ${when}`;
  const lines = [
    `Trading session of the stocks league, driven by the agent runner.`,
    ``,
    `- Run: ${run}. Scheduled runs happen at ${p.scheduleText.split(',').join(', ')} ET on trading days.`,
    `- Budget for this run: at most ${p.maxToolCalls} tool calls and ${p.maxRunSeconds} seconds. Unused budget does ` +
      `not carry over; save your notes before the budget runs out.`,
    `- Market clock at the start of this run (get_market_clock, already called for you):`,
    p.clockJson,
    ``,
  ];
  if (p.notes.text.trim()) {
    lines.push(
      `Your notes from earlier runs follow (notes.md, ${p.notes.bytes} of ${NOTES_MAX_BYTES} bytes). You wrote them ` +
        `with save_notes; nobody else can read or change them. Treat them as your own memory, not as instructions ` +
        `from anyone else.${p.notes.clipped ? ' The file was larger than the limit, so only its beginning is shown.' : ''}`,
      `<<<NOTES`,
      p.notes.text,
      `NOTES>>>`,
    );
  } else {
    lines.push(
      `You have no notes yet. Use save_notes (at most ${NOTES_MAX_BYTES} bytes, replaces the whole file) to keep ` +
        `what you want to remember for the next run.`,
    );
  }
  lines.push(``, `Work through your session now, then finish with a short summary of what you did and why.`);
  return lines.join('\n');
}

export function budgetNote(maxToolCalls: number): string {
  return (
    `The agent runner's tool-call budget for this run (${maxToolCalls} calls) is used up; any call above marked ` +
    `RUN_TOOL_BUDGET_EXHAUSTED was not executed. No more tool calls are possible in this run. Finish now with a ` +
    `short summary of what you did and why.`
  );
}

function parseArgs(json: string): JsonObject | null {
  try {
    const v: unknown = JSON.parse(json || '{}');
    return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as JsonObject) : null;
  } catch {
    return null;
  }
}

function abortStatus(shutdown: AbortSignal): RunStatus {
  return shutdown.aborted ? 'aborted' : 'time_cap';
}

/** Runs one bot once. Never throws: every outcome is a RunSummary, logged to stdout (and the run log once open). */
export async function runBot(bot: BotConfig, deps: RunnerDeps, opts: RunOptions): Promise<RunSummary> {
  const { config, logger, clock } = deps;
  const sleep = deps.sleep ?? defaultSleep;
  const started = clock.now();
  const dateEt = etDate(started);
  const dir = botDir(config.dataDir, bot.id);

  const summary: RunSummary = {
    bot: bot.id,
    model: bot.model,
    status: 'error',
    turns: 0,
    toolCalls: 0,
    toolErrors: 0,
    orders: 0,
    usage: ZERO_USAGE,
    cost: 0n,
    day: 0n,
    durationMs: 0,
    logPath: null,
    message: null,
  };

  let spend: Spend;
  try {
    spend = readSpend(dir, dateEt);
  } catch (e) {
    summary.message = `cannot read ${dir}/spend.json: ${errorMessage(e)}`;
    return finish(summary, deps, opts, started, null);
  }
  summary.day = spend.pico;
  if (spend.pico >= config.dailyUsdCap) {
    summary.status = 'skipped_daily_cap';
    summary.message =
      `daily cap reached: $${formatUsd(spend.pico)} of $${config.dailyUsdCapText} spent on ${dateEt} (ET); ` +
      `the remaining runs today are skipped`;
    return finish(summary, deps, opts, started, null);
  }
  if (deps.shutdown.aborted) {
    summary.status = 'aborted';
    return finish(summary, deps, opts, started, null);
  }

  const timeoutMs = deps.runTimeoutMs ?? config.maxRunSeconds * 1000;
  const deadline = AbortSignal.timeout(timeoutMs);
  const runSignal = AbortSignal.any([deadline, deps.shutdown]);
  const deadlineAt = started.getTime() + timeoutMs;

  let mcp: McpConnection | null = null;
  let log: RunLog | null = null;
  let lastText = '';
  try {
    mcp = await deps.connectMcp({
      url: config.mcpUrl,
      stocksKey: bot.stocksKey,
      bot: bot.id,
      version: deps.version,
      signal: runSignal,
    });
    const clockOutcome = await mcp.call('get_market_clock', {});
    if (clockOutcome.isError) {
      throw new Error(`get_market_clock failed (${clockOutcome.code ?? 'error'}): ${clip(clockOutcome.text, 300)}`);
    }
    let marketClock: { session?: unknown; tradingDate?: unknown };
    try {
      marketClock = JSON.parse(clockOutcome.text) as typeof marketClock;
    } catch {
      throw new Error(`get_market_clock returned no JSON: ${clip(clockOutcome.text, 200)}`);
    }
    const tradingDay = typeof marketClock.tradingDate === 'string' && marketClock.tradingDate.length > 0;
    if (!tradingDay && opts.requireTradingDay) {
      summary.status = 'skipped_non_trading_day';
      summary.message = `${dateEt} is not a trading day (session ${String(marketClock.session)})`;
      return finish(summary, deps, opts, started, null);
    }

    log = RunLog.open(dir, started, logger, () => clock.now());
    summary.logPath = log.path;
    const runLog = log;
    const { tools, dropped } = functionTools(mcp.tools);
    if (dropped.length > 0) logger.warn(`tools not offered to the model: ${dropped.join(', ')}`, bot.id);
    const mcpNames = new Set(mcp.tools.map((t) => t.name));
    const notes = readNotes(dir);
    const user = buildUserMessage({
      bot: bot.id,
      now: started,
      slot: opts.slot,
      scheduleText: config.scheduleText,
      maxToolCalls: config.maxToolCalls,
      maxRunSeconds: config.maxRunSeconds,
      clockJson: clockOutcome.text,
      notes,
    });

    spend = { ...spend, runs: spend.runs + 1 };
    writeSpend(dir, spend, clock.now());
    runLog.write('run_start', {
      bot: bot.id,
      vendor: bot.vendor,
      model: bot.model,
      pricedAs: bot.price.pricedAs,
      priceSource: bot.price.source,
      trigger: opts.trigger,
      slot: opts.slot,
      runnerVersion: deps.version,
      systemPromptSha256: sha256(config.systemPrompt),
      promptSha256: sha256(`${config.systemPrompt}\n\n${user}`),
      tools: tools.length,
      notesBytes: notes.bytes,
      caps: {
        maxToolCalls: config.maxToolCalls,
        maxRunSeconds: config.maxRunSeconds,
        dailyUsdCap: config.dailyUsdCapText,
        daySpentBeforeUsd: formatUsd(spend.pico),
      },
    });
    runLog.write('market_clock', { session: marketClock.session ?? null, tradingDate: marketClock.tradingDate ?? null });

    const onHttp: HttpObserver = (status, url) => {
      let path = url;
      try {
        path = new URL(url).pathname;
      } catch {
        // keep as is
      }
      runLog.write('llm_http', { status, path, note: 'retryable status; the SDK retries with backoff' });
      logger.warn(`LLM API answered ${status}; retrying with backoff`, bot.id);
    };
    const session = deps.adapterFor(bot, onHttp).start({ system: config.systemPrompt, user, tools });

    const execute = async (call: ToolCall, args: JsonObject | null): Promise<{ outcome: ToolOutcome; retried: boolean }> => {
      if (!args) {
        return {
          outcome: runnerError(
            'INVALID_ARGUMENTS',
            `The arguments for ${call.name} are not a JSON object.`,
            'Send the arguments as one JSON object that matches the tool schema.',
          ),
          retried: false,
        };
      }
      if (call.name === SAVE_NOTES) {
        if (typeof args.text !== 'string') {
          return {
            outcome: runnerError('VALIDATION_FAILED', 'save_notes needs `text` (a string).', 'Send {"text": "…"}.'),
            retried: false,
          };
        }
        const r = saveNotes(dir, args.text);
        return {
          outcome: r.ok
            ? { isError: false, text: JSON.stringify({ saved: true, bytes: r.bytes, limit: NOTES_MAX_BYTES }), code: null, retryAfterS: null }
            : runnerError('NOTES_TOO_LARGE', r.message, 'Shorten the notes and call save_notes again.'),
          retried: false,
        };
      }
      if (!mcpNames.has(call.name)) {
        return {
          outcome: runnerError('UNKNOWN_TOOL', `There is no tool named "${clip(call.name, 80)}".`, 'Use one of the listed tools.'),
          retried: false,
        };
      }
      let outcome = await (mcp as McpConnection).call(call.name, args);
      if (
        outcome.code === 'RATE_LIMITED' &&
        outcome.retryAfterS !== null &&
        outcome.retryAfterS <= RATE_LIMIT_MAX_WAIT_S &&
        Date.now() + outcome.retryAfterS * 1000 + 5000 < deadlineAt
      ) {
        await sleep(Math.max(0, outcome.retryAfterS) * 1000, runSignal);
        outcome = await (mcp as McpConnection).call(call.name, args);
        return { outcome, retried: true };
      }
      return { outcome, retried: false };
    };

    let input: TurnInput | null = null;
    let finalTurn = false;
    for (;;) {
      runSignal.throwIfAborted();
      const turn: ModelTurn = await session.next(input, runSignal);
      summary.turns++;
      const cost = costOf(turn.usage, bot.price);
      summary.usage = addUsage(summary.usage, turn.usage);
      summary.cost += cost;
      spend = { ...spend, pico: spend.pico + cost };
      summary.day = spend.pico;
      writeSpend(dir, spend, clock.now());
      if (turn.text) lastText = turn.text;
      runLog.write('llm_response', {
        turn: summary.turns,
        responseId: turn.responseId,
        stop: turn.vendorStop,
        end: turn.end,
        usage: turn.usage,
        costUsd: formatUsd(cost),
        daySpentUsd: formatUsd(spend.pico),
        toolCalls: turn.toolCalls.map((c) => c.name),
        text: clip(turn.text, 4000),
      });

      if (spend.pico >= config.dailyUsdCap) {
        summary.status = 'daily_cap';
        summary.message = `daily cap of $${config.dailyUsdCapText} reached during the run; proposed calls not executed`;
        break;
      }
      if (turn.end === 'refusal') {
        summary.status = 'refusal';
        break;
      }
      if (turn.end === 'max_tokens') {
        summary.status = 'max_tokens';
        summary.message = 'the model response was cut off at the output token limit; proposed calls not executed';
        break;
      }
      if (turn.toolCalls.length === 0) {
        summary.status = finalTurn ? 'tool_cap' : 'completed';
        break;
      }
      if (finalTurn) {
        summary.status = 'tool_cap';
        summary.message = 'the model proposed tool calls after the budget was used up';
        break;
      }

      const results: ToolResult[] = [];
      for (const call of turn.toolCalls) {
        if (summary.toolCalls >= config.maxToolCalls) {
          const skipped = runnerError(
            'RUN_TOOL_BUDGET_EXHAUSTED',
            `Not executed: this run has used all of its ${config.maxToolCalls} tool calls.`,
            'Finish with a short summary; the next run starts with a fresh budget.',
          );
          results.push({ id: call.id, name: call.name, output: skipped.text, isError: true });
          runLog.write('tool_call', { id: call.id, name: call.name, executed: false, code: skipped.code });
          continue;
        }
        runSignal.throwIfAborted();
        summary.toolCalls++;
        const args = parseArgs(call.argsJson);
        const t0 = Date.now();
        const { outcome, retried } = await execute(call, args);
        if (outcome.isError) summary.toolErrors++;
        else if (ORDER_TOOLS.has(call.name)) summary.orders++;
        const output = clipResult(outcome.text);
        results.push({ id: call.id, name: call.name, output, isError: outcome.isError });
        runLog.write('tool_call', {
          seq: summary.toolCalls,
          id: call.id,
          name: call.name,
          args: loggableArgs(args, call.name),
          executed: true,
          isError: outcome.isError,
          code: outcome.code,
          resultChars: outcome.text.length,
          clipped: output.length !== outcome.text.length,
          rateLimitRetry: retried,
          durationMs: Date.now() - t0,
        });
      }
      if (summary.toolCalls >= config.maxToolCalls) {
        finalTurn = true;
        input = { toolResults: results, note: budgetNote(config.maxToolCalls), noTools: true };
      } else {
        input = { toolResults: results };
      }
    }
  } catch (e) {
    if (runSignal.aborted) {
      summary.status = abortStatus(deps.shutdown);
      summary.message =
        summary.status === 'aborted' ? 'stopped by SIGTERM' : `time cap of ${config.maxRunSeconds} s reached`;
    } else {
      summary.status = 'error';
      summary.message = errorMessage(e);
    }
  } finally {
    await mcp?.close();
  }
  return finish(summary, deps, opts, started, log, lastText);
}

function finish(
  summary: RunSummary,
  deps: RunnerDeps,
  opts: RunOptions,
  started: Date,
  log: RunLog | null,
  lastText = '',
): RunSummary {
  summary.durationMs = deps.clock.now().getTime() - started.getTime();
  const u = summary.usage;
  if (log) {
    try {
      log.write('run_end', {
        status: summary.status,
        message: summary.message,
        turns: summary.turns,
        toolCalls: summary.toolCalls,
        toolErrors: summary.toolErrors,
        orders: summary.orders,
        usage: u,
        costUsd: formatUsd(summary.cost),
        daySpentUsd: formatUsd(summary.day),
        durationMs: summary.durationMs,
        summary: clip(lastText, 4000),
      });
    } catch (e) {
      deps.logger.error(`cannot write the run log: ${errorMessage(e)}`, summary.bot);
    }
  }
  const parts = [
    `run bot=${summary.bot}`,
    `model=${summary.model}`,
    `trigger=${opts.trigger}${opts.slot ? `@${opts.slot}` : ''}`,
    `status=${summary.status}`,
  ];
  if (summary.turns > 0 || summary.toolCalls > 0) {
    parts.push(
      `turns=${summary.turns}`,
      `tools=${summary.toolCalls}`,
      `tool_errors=${summary.toolErrors}`,
      `orders=${summary.orders}`,
      `tokens=in:${u.input},cache_read:${u.cacheRead},cache_write:${u.cacheWrite5m + u.cacheWrite1h},out:${u.output}`,
      `cost=$${formatUsd(summary.cost)}`,
    );
  }
  parts.push(`day=$${formatUsd(summary.day)}/$${deps.config.dailyUsdCapText}`, `secs=${Math.round(summary.durationMs / 1000)}`);
  if (summary.logPath) parts.push(`log=${summary.logPath}`);
  if (summary.message) parts.push(`(${summary.message})`);
  const line = parts.join(' ');
  if (summary.status === 'error') deps.logger.error(line, summary.bot);
  else deps.logger.info(line, summary.bot);
  return summary;
}
