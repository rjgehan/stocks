// MCP tool definitions → plain function tools for the vendors (ARCHITECTURE §20.5). The model only proposes calls;
// the runner executes them (MCP tools through the stocks server; save_notes, schedule_run and cancel_scheduled_run
// locally).

import type Anthropic from '@anthropic-ai/sdk';
import type OpenAI from 'openai';
import { NOTES_MAX_BYTES } from './config.js';
import { CRYPTO_WINDOW_ET, MAX_AHEAD_MS, MIN_GAP_MS, MIN_LEAD_MS, REASON_MAX, WINDOW_ET } from './requested.js';

export type JsonObject = { [key: string]: unknown };

/** A vendor-neutral function tool. */
export interface FunctionTool {
  name: string;
  description: string;
  parameters: JsonObject;
}

/** The subset of an MCP `tools/list` entry the runner uses. */
export interface McpToolInfo {
  name: string;
  title?: string | undefined;
  description?: string | undefined;
  inputSchema: JsonObject;
}

export const SAVE_NOTES = 'save_notes';

export const SAVE_NOTES_TOOL: FunctionTool = {
  name: SAVE_NOTES,
  description:
    `Replace your private notes (notes.md, at most ${NOTES_MAX_BYTES} bytes of UTF-8) with \`text\`. The notes are ` +
    'shown to you at the start of every run and nobody else reads them: keep your plan, the thesis behind each ' +
    'position, open orders to revisit and lessons learned. Each call replaces the whole file. This tool is provided ' +
    'by the agent runner, not by the stocks server.',
  parameters: {
    type: 'object',
    properties: {
      text: {
        type: 'string',
        description: `The complete new notes (replaces the previous notes; at most ${NOTES_MAX_BYTES} bytes).`,
      },
    },
    required: ['text'],
    additionalProperties: false,
  },
};

export const SCHEDULE_RUN = 'schedule_run';
export const CANCEL_SCHEDULED_RUN = 'cancel_scheduled_run';

/**
 * The runner-local tools for booking extra runs (docs/agents.md §2.5); none when the per-day limit is 0. `crypto`:
 * the run's market clock says crypto trades 24/7, so any day and time can be booked.
 */
export function scheduleRunTools(perDay: number, crypto = false): FunctionTool[] {
  if (perDay <= 0) return [];
  const when = crypto
    ? `may be on any day, weekends and market holidays included, at any time (${CRYPTO_WINDOW_ET.text} ET), while ` +
      'crypto trading is on (on a stock-market closed day only crypto pairs trade), and must be '
    : `must be on a trading day between ${WINDOW_ET.text} ET, `;
  return [
    {
      name: SCHEDULE_RUN,
      description:
        'Book an extra run of yourself at a time you choose, on top of your scheduled runs, to act at a planned ' +
        'moment: after an earnings release or other news, to re-check a price level, or to manage an order or ' +
        'position later in the day. `at_et` is America/New_York time, "YYYY-MM-DD HH:MM" or "HH:MM" for today. It ' +
        `${when}at least ${MIN_LEAD_MS / 60_000} minutes from now, ` +
        `at most ${MAX_AHEAD_MS / 86_400_000} days ahead, and at least ${MIN_GAP_MS / 60_000} minutes away from your ` +
        `scheduled runs and your other extra runs; at most ${perDay} extra runs per day. Extra runs spend the same ` +
        `daily budget as scheduled runs. \`reason\` (1-${REASON_MAX} characters) is shown to you when that run ` +
        'starts. Returns the booked run and all your pending extra runs. This tool is provided by the agent runner, ' +
        'not by the stocks server.',
      parameters: {
        type: 'object',
        properties: {
          at_et: { type: 'string', description: 'When to run, ET: "YYYY-MM-DD HH:MM", or "HH:MM" for today.' },
          reason: { type: 'string', description: `What to check or do in that run (1-${REASON_MAX} characters).` },
        },
        required: ['at_et', 'reason'],
        additionalProperties: false,
      },
    },
    {
      name: CANCEL_SCHEDULED_RUN,
      description:
        'Cancel one of your pending extra runs (booked with schedule_run) by its `id`. Returns your remaining ' +
        'pending extra runs. This tool is provided by the agent runner, not by the stocks server.',
      parameters: {
        type: 'object',
        properties: { id: { type: 'string', description: 'The id schedule_run returned, e.g. "r1a2b3c".' } },
        required: ['id'],
        additionalProperties: false,
      },
    },
  ];
}

/** Both vendors accept 1–64 characters of letters, digits, underscore and hyphen. */
export const TOOL_NAME = /^[a-zA-Z0-9_-]{1,64}$/;

const DROP_KEYS = new Set(['$schema', '$id']);

/** A copy of an MCP input schema the function-calling APIs accept: an object schema without `$schema`/`$id`. */
export function toParameters(schema: JsonObject | undefined): JsonObject {
  const out: JsonObject = {};
  for (const [k, v] of Object.entries(schema ?? {})) {
    if (!DROP_KEYS.has(k)) out[k] = structuredClone(v);
  }
  out.type = 'object';
  const props = out.properties;
  if (props === null || typeof props !== 'object' || Array.isArray(props)) out.properties = {};
  return out;
}

export interface ConvertResult {
  tools: FunctionTool[];
  /** Names the runner could not offer (invalid for the vendors, or shadowing a runner-local tool). */
  dropped: string[];
}

/** MCP tools (server order) + the runner-local tools (save_notes last). */
export function functionTools(mcpTools: McpToolInfo[], local: FunctionTool[] = []): ConvertResult {
  const tools: FunctionTool[] = [];
  const dropped: string[] = [];
  const seen = new Set<string>([SAVE_NOTES, ...local.map((t) => t.name)]);
  for (const t of mcpTools) {
    if (!TOOL_NAME.test(t.name) || seen.has(t.name)) {
      dropped.push(t.name);
      continue;
    }
    seen.add(t.name);
    const description = (t.description ?? '').trim() || (t.title ?? '').trim() || t.name;
    tools.push({ name: t.name, description, parameters: toParameters(t.inputSchema) });
  }
  tools.push(...local, SAVE_NOTES_TOOL);
  return { tools, dropped };
}

/** Anthropic Messages API custom tools. */
export function toAnthropicTools(tools: FunctionTool[]): Anthropic.Tool[] {
  return tools.map((t) => ({
    name: t.name,
    description: t.description,
    input_schema: t.parameters as Anthropic.Tool.InputSchema,
  }));
}

/** OpenAI Responses API function tools (non-strict: the stocks schemas have optional members). */
export function toOpenAITools(tools: FunctionTool[]): OpenAI.Responses.FunctionTool[] {
  return tools.map((t) => ({
    type: 'function',
    name: t.name,
    description: t.description,
    parameters: t.parameters,
    strict: false,
  }));
}
