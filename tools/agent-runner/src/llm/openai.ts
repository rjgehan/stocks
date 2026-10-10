// ChatGPT through the OpenAI Responses API with plain function tools (no remote MCP tool). Conversation state is
// chained with previous_response_id (reasoning items stay with OpenAI), so each turn sends only the new function
// outputs; instructions and tools are re-sent every turn because they are not carried over.

import OpenAI from 'openai';
import { LLM_MAX_RETRIES, MAX_OUTPUT_TOKENS } from '../config.js';
import type { TokenUsage } from '../pricing.js';
import { toOpenAITools } from '../tools.js';
import type { HttpObserver, ModelAdapter, ModelSession, ModelTurn, StartParams, TurnEnd, TurnInput } from './types.js';
import { observingFetch } from './types.js';

export interface OpenAIOptions {
  apiKey: string;
  model: string;
  baseURL?: string | undefined;
  onHttpStatus?: HttpObserver | undefined;
  requestTimeoutMs?: number | undefined;
}

export function openaiUsage(u: OpenAI.Responses.ResponseUsage | undefined | null): TokenUsage {
  if (!u) return { input: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, output: 0 };
  const cached = u.input_tokens_details?.cached_tokens ?? 0;
  const writes = u.input_tokens_details?.cache_write_tokens ?? 0;
  return {
    input: Math.max(0, (u.input_tokens ?? 0) - cached - writes),
    cacheRead: cached,
    cacheWrite5m: writes,
    cacheWrite1h: 0,
    output: u.output_tokens ?? 0,
  };
}

/** What the model sees for a tool result: the server's text unchanged, marked when it is an error result. */
export function functionOutput(output: string, isError: boolean): string {
  const body = output || '(empty result)';
  return isError ? `Tool error (isError=true): ${body}` : body;
}

export class OpenAIAdapter implements ModelAdapter {
  readonly vendor = 'openai' as const;
  readonly model: string;
  private readonly client: OpenAI;
  private readonly requestTimeoutMs: number;

  constructor(opts: OpenAIOptions) {
    this.model = opts.model;
    this.requestTimeoutMs = opts.requestTimeoutMs ?? 180_000;
    this.client = new OpenAI({
      apiKey: opts.apiKey,
      baseURL: opts.baseURL ?? null,
      maxRetries: LLM_MAX_RETRIES,
      fetch: observingFetch(opts.onHttpStatus),
    });
  }

  start(params: StartParams): ModelSession {
    const tools = toOpenAITools(params.tools);
    const { client, model, requestTimeoutMs } = this;
    let previousResponseId: string | null = null;

    return {
      async next(input: TurnInput | null, signal: AbortSignal): Promise<ModelTurn> {
        const items: OpenAI.Responses.ResponseInputItem[] = [];
        if (!input) {
          items.push({ role: 'user', content: params.user });
        } else {
          for (const r of input.toolResults) {
            items.push({ type: 'function_call_output', call_id: r.id, output: functionOutput(r.output, r.isError) });
          }
          if (input.note) items.push({ role: 'user', content: input.note });
        }
        const response = await client.responses.create(
          {
            model,
            instructions: params.system,
            input: items,
            tools,
            max_output_tokens: MAX_OUTPUT_TOKENS,
            store: true,
            ...(previousResponseId ? { previous_response_id: previousResponseId } : {}),
            ...(input?.noTools ? { tool_choice: 'none' as const } : {}),
          },
          { signal, timeout: requestTimeoutMs },
        );
        previousResponseId = response.id;

        const toolCalls: ModelTurn['toolCalls'] = [];
        const texts: string[] = [];
        let refused = false;
        for (const item of response.output ?? []) {
          if (item.type === 'function_call') {
            toolCalls.push({ id: item.call_id, name: item.name, argsJson: item.arguments || '{}' });
          } else if (item.type === 'message') {
            for (const c of item.content) {
              if (c.type === 'output_text') texts.push(c.text);
              else if (c.type === 'refusal') {
                refused = true;
                texts.push(c.refusal);
              }
            }
          }
        }
        let end: TurnEnd = toolCalls.length > 0 ? 'tool_calls' : 'end';
        if (response.status === 'incomplete') {
          const reason = response.incomplete_details?.reason;
          end = reason === 'content_filter' ? 'refusal' : 'max_tokens';
        } else if (refused && toolCalls.length === 0) {
          end = 'refusal';
        }
        return {
          text: texts.join('\n').trim(),
          toolCalls,
          usage: openaiUsage(response.usage),
          end,
          vendorStop: response.status ?? 'null',
          responseId: response.id ?? null,
        };
      },
    };
  }
}
