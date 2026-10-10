// Local fakes of the Anthropic Messages API and the OpenAI Responses API: HTTP servers on 127.0.0.1 that answer with
// scripted tool calls and usage numbers. The SDKs are pointed at them through baseURL, so tests never reach a vendor.

import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

export interface RecordedRequest {
  method: string;
  path: string;
  headers: IncomingHttpHeaders;
  body: any;
}

export interface Reply {
  status?: number;
  headers?: Record<string, string>;
  body: unknown;
  /** Delay before answering (ms). */
  delayMs?: number;
}

export type Step = (req: RecordedRequest, index: number) => Reply;

export class ScriptedLlmServer {
  readonly requests: RecordedRequest[] = [];
  private server: Server | null = null;
  private next = 0;
  url = '';

  constructor(
    private readonly expectedPath: string,
    private readonly steps: Step[],
  ) {}

  async start(): Promise<this> {
    this.server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (c: Buffer) => chunks.push(c));
      req.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let body: unknown = null;
        try {
          body = raw ? JSON.parse(raw) : null;
        } catch {
          body = raw;
        }
        const recorded: RecordedRequest = {
          method: req.method ?? '',
          path: (req.url ?? '').split('?')[0] ?? '',
          headers: req.headers,
          body,
        };
        this.requests.push(recorded);
        let reply: Reply;
        if (recorded.method !== 'POST' || recorded.path !== this.expectedPath) {
          reply = { status: 404, body: { error: { type: 'not_found', message: `no route ${recorded.path}` } } };
        } else {
          const step = this.steps[this.next];
          if (!step) {
            reply = { status: 500, body: { error: { type: 'api_error', message: 'fake script exhausted' } } };
          } else {
            this.next++;
            reply = step(recorded, this.next - 1);
          }
        }
        const send = (): void => {
          if (res.destroyed) return;
          res.writeHead(reply.status ?? 200, { 'content-type': 'application/json', ...reply.headers });
          res.end(JSON.stringify(reply.body));
        };
        if (reply.delayMs) setTimeout(send, reply.delayMs);
        else send();
      });
    });
    await new Promise<void>((resolve) => this.server?.listen(0, '127.0.0.1', resolve));
    const { port } = this.server.address() as AddressInfo;
    this.url = `http://127.0.0.1:${port}`;
    return this;
  }

  /** Requests that reached the scripted route (not 404s). */
  get calls(): RecordedRequest[] {
    return this.requests.filter((r) => r.path === this.expectedPath);
  }

  async close(): Promise<void> {
    const s = this.server;
    if (!s) return;
    s.closeAllConnections();
    await new Promise<void>((resolve) => s.close(() => resolve()));
  }
}

// ------------------------------------------------------------------------------------------------ Anthropic

export interface ClaudeBlock {
  type: 'text' | 'tool_use' | 'thinking';
  [key: string]: unknown;
}

export const text = (t: string): ClaudeBlock => ({ type: 'text', text: t });
export const toolUse = (id: string, name: string, input: unknown): ClaudeBlock => ({ type: 'tool_use', id, name, input });

export interface ClaudeUsage {
  input_tokens: number;
  output_tokens: number;
  cache_read_input_tokens?: number;
  cache_creation_input_tokens?: number;
}

let msgSeq = 0;

export function claudeReply(
  content: ClaudeBlock[],
  usage: ClaudeUsage = { input_tokens: 1000, output_tokens: 100 },
  stopReason?: string,
): Reply {
  const stop = stopReason ?? (content.some((b) => b.type === 'tool_use') ? 'tool_use' : 'end_turn');
  return {
    body: {
      id: `msg_fake_${++msgSeq}`,
      type: 'message',
      role: 'assistant',
      model: 'fake-model',
      content,
      stop_reason: stop,
      stop_sequence: null,
      usage: { cache_creation_input_tokens: 0, cache_read_input_tokens: 0, ...usage },
    },
  };
}

export function anthropicServer(steps: Step[]): ScriptedLlmServer {
  return new ScriptedLlmServer('/v1/messages', steps);
}

// ------------------------------------------------------------------------------------------------ OpenAI

export interface OpenAIItem {
  type: 'function_call' | 'message' | 'reasoning';
  [key: string]: unknown;
}

export const fnCall = (callId: string, name: string, args: unknown): OpenAIItem => ({
  type: 'function_call',
  id: `fc_${callId}`,
  call_id: callId,
  name,
  arguments: JSON.stringify(args),
  status: 'completed',
});

export const message = (t: string): OpenAIItem => ({
  type: 'message',
  id: `msg_${t.length}`,
  role: 'assistant',
  status: 'completed',
  content: [{ type: 'output_text', text: t, annotations: [] }],
});

export interface OpenAIUsage {
  input_tokens: number;
  output_tokens: number;
  cached_tokens?: number;
  cache_write_tokens?: number;
}

let respSeq = 0;

export function openaiReply(
  output: OpenAIItem[],
  usage: OpenAIUsage = { input_tokens: 1000, output_tokens: 100 },
  status: 'completed' | 'incomplete' = 'completed',
): Reply {
  const id = `resp_fake_${++respSeq}`;
  return {
    body: {
      id,
      object: 'response',
      created_at: 1_760_000_000,
      status,
      incomplete_details: status === 'incomplete' ? { reason: 'max_output_tokens' } : null,
      model: 'fake-model',
      output,
      parallel_tool_calls: true,
      tools: [],
      usage: {
        input_tokens: usage.input_tokens,
        input_tokens_details: { cached_tokens: usage.cached_tokens ?? 0, cache_write_tokens: usage.cache_write_tokens ?? 0 },
        output_tokens: usage.output_tokens,
        output_tokens_details: { reasoning_tokens: 0 },
        total_tokens: usage.input_tokens + usage.output_tokens,
      },
    },
  };
}

export function openaiServer(steps: Step[]): ScriptedLlmServer {
  return new ScriptedLlmServer('/v1/responses', steps);
}

/** Every tool_result block of the last user message of an Anthropic request. */
export function lastToolResults(req: RecordedRequest): any[] {
  const msgs = req.body.messages as any[];
  const last = msgs[msgs.length - 1];
  return Array.isArray(last.content)
    ?
      last.content.filter((b: any) => b.type === 'tool_result')
    : [];
}
