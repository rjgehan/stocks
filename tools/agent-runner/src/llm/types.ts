// The model-agnostic side of the loop: one conversation per run, driven the same way for both vendors.

import type { TokenUsage } from '../pricing.js';
import type { FunctionTool } from '../tools.js';

export interface ToolCall {
  /** The vendor's id for the call (Anthropic tool_use id, OpenAI call_id). */
  id: string;
  name: string;
  /** The raw JSON arguments the model proposed (parsed and checked by the runner). */
  argsJson: string;
}

export type TurnEnd =
  | 'tool_calls' // the model proposed calls; the runner executes them and continues
  | 'end' // the model finished
  | 'max_tokens' // the response was cut off at MAX_OUTPUT_TOKENS
  | 'refusal'; // the vendor's safety system declined

export interface ModelTurn {
  text: string;
  toolCalls: ToolCall[];
  usage: TokenUsage;
  end: TurnEnd;
  /** The vendor's own stop reason / status, for the run log. */
  vendorStop: string;
  responseId: string | null;
}

export interface ToolResult {
  id: string;
  name: string;
  output: string;
  isError: boolean;
}

export interface TurnInput {
  toolResults: ToolResult[];
  /** Extra runner text after the results (e.g. "the tool budget is used up"). */
  note?: string | undefined;
  /** Forbid further tool calls in this turn (the final turn after the budget ran out). */
  noTools?: boolean | undefined;
}

export interface ModelSession {
  /** First call: sends the opening user message. Later calls: the results of the previous turn's tool calls. */
  next(input: TurnInput | null, signal: AbortSignal): Promise<ModelTurn>;
}

export interface StartParams {
  system: string;
  user: string;
  tools: FunctionTool[];
}

export interface ModelAdapter {
  readonly vendor: 'anthropic' | 'openai';
  readonly model: string;
  start(params: StartParams): ModelSession;
}

/** Called for every HTTP response the SDK receives with a retryable status (429, 5xx), before it retries. */
export type HttpObserver = (status: number, attemptUrl: string) => void;

/** A fetch that reports retryable statuses to the run log (the SDKs do the backoff and retries themselves). */
export function observingFetch(observer: HttpObserver | undefined): typeof fetch {
  return async (input, init) => {
    const res = await fetch(input, init);
    if (observer && (res.status === 429 || res.status >= 500)) {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      observer(res.status, url);
    }
    return res;
  };
}
