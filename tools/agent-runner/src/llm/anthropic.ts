// Claude through the Anthropic Messages API with plain custom tools (no MCP connector, no beta header). The whole
// conversation is re-sent each turn (thinking blocks included, unchanged), with automatic prompt caching so the
// stable prefix (tools, system prompt, earlier turns) is billed at the cache-read price.

import Anthropic from '@anthropic-ai/sdk';
import { LLM_MAX_RETRIES, MAX_OUTPUT_TOKENS } from '../config.js';
import type { TokenUsage } from '../pricing.js';
import { addUsage, ZERO_USAGE } from '../pricing.js';
import { toAnthropicTools } from '../tools.js';
import type { HttpObserver, ModelAdapter, ModelSession, ModelTurn, StartParams, TurnEnd, TurnInput } from './types.js';
import { observingFetch } from './types.js';

export interface AnthropicOptions {
  apiKey: string;
  model: string;
  baseURL?: string | undefined;
  onHttpStatus?: HttpObserver | undefined;
  /** Per-request timeout before the SDK retries (ms). */
  requestTimeoutMs?: number | undefined;
}

export function anthropicUsage(u: Anthropic.Usage): TokenUsage {
  const writes = u.cache_creation_input_tokens ?? 0;
  let w5m = writes;
  let w1h = 0;
  if (u.cache_creation) {
    w5m = u.cache_creation.ephemeral_5m_input_tokens ?? 0;
    w1h = u.cache_creation.ephemeral_1h_input_tokens ?? 0;
    if (w5m + w1h < writes) w5m += writes - w5m - w1h;
  }
  return {
    input: u.input_tokens ?? 0,
    cacheRead: u.cache_read_input_tokens ?? 0,
    cacheWrite5m: w5m,
    cacheWrite1h: w1h,
    output: u.output_tokens ?? 0,
  };
}

function endOf(stop: Anthropic.StopReason | null, toolCalls: number): TurnEnd {
  switch (stop) {
    case 'tool_use':
      return toolCalls > 0 ? 'tool_calls' : 'end';
    case 'max_tokens':
      return 'max_tokens';
    case 'refusal':
      return 'refusal';
    default:
      return toolCalls > 0 ? 'tool_calls' : 'end';
  }
}

export class AnthropicAdapter implements ModelAdapter {
  readonly vendor = 'anthropic' as const;
  readonly model: string;
  private readonly client: Anthropic;
  private readonly requestTimeoutMs: number;

  constructor(opts: AnthropicOptions) {
    this.model = opts.model;
    this.requestTimeoutMs = opts.requestTimeoutMs ?? 180_000;
    this.client = new Anthropic({
      apiKey: opts.apiKey,
      baseURL: opts.baseURL ?? null,
      maxRetries: LLM_MAX_RETRIES,
      fetch: observingFetch(opts.onHttpStatus),
    });
  }

  start(params: StartParams): ModelSession {
    const tools = toAnthropicTools(params.tools);
    const messages: Anthropic.MessageParam[] = [{ role: 'user', content: params.user }];
    const { client, model, requestTimeoutMs } = this;

    return {
      async next(input: TurnInput | null, signal: AbortSignal): Promise<ModelTurn> {
        if (input) {
          const content: Anthropic.ContentBlockParam[] = input.toolResults.map((r) => ({
            type: 'tool_result',
            tool_use_id: r.id,
            content: r.output || '(empty result)',
            is_error: r.isError,
          }));
          if (input.note) content.push({ type: 'text', text: input.note });
          if (content.length > 0) messages.push({ role: 'user', content });
        }
        let usage = ZERO_USAGE;
        // A paused turn (server-side work in progress) is resumed by sending the assistant turn back unchanged.
        for (let attempt = 0; ; attempt++) {
          const response = await client.messages.create(
            {
              model,
              max_tokens: MAX_OUTPUT_TOKENS,
              system: params.system,
              tools,
              messages,
              cache_control: { type: 'ephemeral' },
              ...(input?.noTools ? { tool_choice: { type: 'none' as const } } : {}),
            },
            { signal, timeout: requestTimeoutMs },
          );
          usage = addUsage(usage, anthropicUsage(response.usage));
          messages.push({ role: 'assistant', content: response.content });
          if (response.stop_reason === 'pause_turn' && attempt < 3) continue;
          const toolCalls = response.content
            .filter((b): b is Anthropic.ToolUseBlock => b.type === 'tool_use')
            .map((b) => ({ id: b.id, name: b.name, argsJson: JSON.stringify(b.input ?? {}) }));
          const text = response.content
            .filter((b): b is Anthropic.TextBlock => b.type === 'text')
            .map((b) => b.text)
            .join('\n')
            .trim();
          return {
            text,
            toolCalls,
            usage,
            end: endOf(response.stop_reason, toolCalls.length),
            vendorStop: response.stop_reason ?? 'null',
            responseId: response.id ?? null,
          };
        }
      },
    };
  }
}
