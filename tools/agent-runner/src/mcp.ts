// MCP Streamable HTTP client to the stocks server (ARCHITECTURE §20.1): `Authorization: Bearer <bot key>` and
// `X-Stocks-Client: agent-runner/<bot>` on every request. The server is stateless, so one connection per run.

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import type { JsonSchemaValidator, jsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/types.js';
import { MCP_CALL_TIMEOUT_MS } from './config.js';
import type { JsonObject, McpToolInfo } from './tools.js';

/** The result of one tool call, as the model will see it. `text` keeps the server's JSON (and `untrusted`) as is. */
export interface ToolOutcome {
  isError: boolean;
  text: string;
  /** The stable error `code` of an error result (§20.2), or a runner code such as RUNNER_MCP_ERROR. */
  code: string | null;
  retryAfterS: number | null;
}

export interface McpConnection {
  tools: McpToolInfo[];
  call(name: string, args: JsonObject): Promise<ToolOutcome>;
  close(): Promise<void>;
}

export interface McpConnectOptions {
  url: string;
  stocksKey: string;
  bot: string;
  version: string;
  signal?: AbortSignal | undefined;
}

export type ConnectMcp = (opts: McpConnectOptions) => Promise<McpConnection>;

interface ContentBlock {
  type: string;
  text?: unknown;
}

function errorFields(text: string): { code: string | null; retryAfterS: number | null } {
  try {
    const j = JSON.parse(text) as { code?: unknown; retry_after_s?: unknown };
    return {
      code: typeof j.code === 'string' ? j.code : null,
      retryAfterS: typeof j.retry_after_s === 'number' ? j.retry_after_s : null,
    };
  } catch {
    return { code: null, retryAfterS: null };
  }
}

/** Turns a `tools/call` result into what the model sees: the text blocks, or the structured content as JSON. */
export function outcomeOf(raw: object): ToolOutcome {
  const result = raw as { content?: unknown; structuredContent?: unknown; isError?: unknown };
  const blocks = Array.isArray(result.content) ? (result.content as ContentBlock[]) : [];
  const texts = blocks.filter((b) => b.type === 'text' && typeof b.text === 'string').map((b) => b.text as string);
  let text = texts.join('\n');
  if (!text && result.structuredContent !== undefined) text = JSON.stringify(result.structuredContent);
  const isError = result.isError === true;
  const { code, retryAfterS } = isError ? errorFields(text) : { code: null, retryAfterS: null };
  return { isError, text, code, retryAfterS };
}

/** A runner-side failure shaped like a server error result, so the model reads every error the same way. */
export function runnerError(code: string, message: string, hint: string): ToolOutcome {
  return {
    isError: true,
    text: JSON.stringify({ code, message, hint, fields: [], retry_after_s: null }),
    code,
    retryAfterS: null,
  };
}

/**
 * Accepts every structured result without client-side checks. The stocks server already validates each result
 * against its own output schema before sending it (§20.1), the model reads the text block either way, and the SDK's
 * default Ajv validator would compile all 38 output schemas per run and warn on stdout about the custom
 * `format: "decimal"` for every money field.
 */
export const trustServerOutput: jsonSchemaValidator = {
  getValidator<T>(): JsonSchemaValidator<T> {
    return (input: unknown) => ({ valid: true, data: input as T, errorMessage: undefined });
  },
};

export const connectMcp: ConnectMcp = async (opts) => {
  const transport = new StreamableHTTPClientTransport(new URL(opts.url), {
    requestInit: {
      headers: {
        Authorization: `Bearer ${opts.stocksKey}`,
        'X-Stocks-Client': `agent-runner/${opts.bot}`,
      },
    },
  });
  const client = new Client(
    { name: 'stocks-agent-runner', version: opts.version },
    { capabilities: {}, jsonSchemaValidator: trustServerOutput },
  );
  await client.connect(transport, { signal: opts.signal, timeout: MCP_CALL_TIMEOUT_MS });
  const tools: McpToolInfo[] = [];
  let cursor: string | undefined;
  do {
    const page = await client.listTools(cursor ? { cursor } : undefined, {
      signal: opts.signal,
      timeout: MCP_CALL_TIMEOUT_MS,
    });
    for (const t of page.tools) {
      tools.push({
        name: t.name,
        title: t.title,
        description: t.description,
        inputSchema: t.inputSchema as JsonObject,
      });
    }
    cursor = page.nextCursor;
  } while (cursor);

  return {
    tools,
    async call(name, args) {
      try {
        const result = await client.callTool({ name, arguments: args }, undefined, { timeout: MCP_CALL_TIMEOUT_MS });
        return outcomeOf(result);
      } catch (e) {
        return runnerError(
          'RUNNER_MCP_ERROR',
          `The agent runner could not complete the call: ${(e as Error).message}`.slice(0, 500),
          'The stocks server did not answer normally. Retry once later in the run, or move on.',
        );
      }
    },
    async close() {
      await client.close().catch(() => undefined);
    },
  };
};
