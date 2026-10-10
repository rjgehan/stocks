// MCP tool definitions → plain function tools for the vendors (ARCHITECTURE §20.5). The model only proposes calls;
// the runner executes them (MCP tools through the stocks server, save_notes locally).

import type Anthropic from '@anthropic-ai/sdk';
import type OpenAI from 'openai';
import { NOTES_MAX_BYTES } from './config.js';

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
  /** Names the runner could not offer (invalid for the vendors, or shadowing save_notes). */
  dropped: string[];
}

/** MCP tools (server order) + save_notes. */
export function functionTools(mcpTools: McpToolInfo[]): ConvertResult {
  const tools: FunctionTool[] = [];
  const dropped: string[] = [];
  const seen = new Set<string>();
  for (const t of mcpTools) {
    if (!TOOL_NAME.test(t.name) || t.name === SAVE_NOTES || seen.has(t.name)) {
      dropped.push(t.name);
      continue;
    }
    seen.add(t.name);
    const description = (t.description ?? '').trim() || (t.title ?? '').trim() || t.name;
    tools.push({ name: t.name, description, parameters: toParameters(t.inputSchema) });
  }
  tools.push(SAVE_NOTES_TOOL);
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
