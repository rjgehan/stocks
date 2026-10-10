import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { connectMcp } from '../src/mcp.js';

// A minimal stateless Streamable HTTP MCP server (plain JSON answers, GET 405), like the stocks server (§20.1).
interface Seen {
  method: string;
  rpc: string | null;
  headers: IncomingHttpHeaders;
}

let server: Server | null = null;

async function fakeServer(seen: Seen[]): Promise<string> {
  server = createServer((req, res) => {
    let raw = '';
    req.on('data', (c: Buffer) => (raw += c.toString('utf8')));
    req.on('end', () => {
      const msg = raw ? (JSON.parse(raw) as { id?: number; method: string; params?: { name?: string } }) : null;
      seen.push({ method: req.method ?? '', rpc: msg?.method ?? null, headers: req.headers });
      if (req.method !== 'POST' || !msg) {
        res.writeHead(405).end();
        return;
      }
      if (msg.id === undefined) {
        res.writeHead(202).end();
        return;
      }
      let result: unknown;
      if (msg.method === 'initialize') {
        result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'stocks', version: 't' } };
      } else if (msg.method === 'tools/list') {
        result = {
          tools: [
            { name: 'whoami', description: 'Who am I', inputSchema: { type: 'object', properties: {} } },
            {
              name: 'get_account',
              description: 'Account',
              inputSchema: { type: 'object', properties: {} },
              outputSchema: { type: 'object', properties: { cash: { type: 'string', format: 'decimal' } } },
            },
          ],
        };
      } else if (msg.method === 'tools/call' && msg.params?.name === 'get_account') {
        result = { content: [{ type: 'text', text: '{"cash":"500.00"}' }], structuredContent: { cash: '500.00' }, isError: false };
      } else {
        result = {
          content: [{ type: 'text', text: '{"code":"NOT_IMPLEMENTED","message":"m","hint":null,"fields":[],"retry_after_s":null}' }],
          isError: true,
        };
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result }));
    });
  });
  await new Promise<void>((resolve) => server?.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${(server!.address() as AddressInfo).port}/mcp`;
}

afterEach(async () => {
  const s = server;
  server = null;
  if (s) {
    s.closeAllConnections();
    await new Promise<void>((resolve) => s.close(() => resolve()));
  }
});

describe('MCP client', () => {
  it('sends the bot key and X-Stocks-Client on every request, lists tools and calls them', async () => {
    const seen: Seen[] = [];
    const url = await fakeServer(seen);
    const conn = await connectMcp({ url, stocksKey: 'stk_test_key', bot: 'claude', version: '9.9.9' });
    expect(conn.tools.map((t) => t.name)).toEqual(['whoami', 'get_account']);
    expect(await conn.call('get_account', {})).toEqual({ isError: false, text: '{"cash":"500.00"}', code: null, retryAfterS: null });
    expect(await conn.call('whoami', {})).toMatchObject({ isError: true, code: 'NOT_IMPLEMENTED' });
    await conn.close();

    const posts = seen.filter((s) => s.method === 'POST');
    expect(posts.map((p) => p.rpc)).toEqual(['initialize', 'notifications/initialized', 'tools/list', 'tools/call', 'tools/call']);
    for (const p of posts) {
      expect(p.headers.authorization).toBe('Bearer stk_test_key');
      expect(p.headers['x-stocks-client']).toBe('agent-runner/claude');
      expect(p.headers.accept).toContain('text/event-stream');
    }
  });

  it('turns a transport failure into a RUNNER_MCP_ERROR result', async () => {
    const seen: Seen[] = [];
    const url = await fakeServer(seen);
    const conn = await connectMcp({ url, stocksKey: 'stk_test_key', bot: 'chatgpt', version: '1' });
    const s = server!;
    s.closeAllConnections();
    await new Promise<void>((resolve) => s.close(() => resolve()));
    server = null;
    const out = await conn.call('get_account', {});
    expect(out).toMatchObject({ isError: true, code: 'RUNNER_MCP_ERROR' });
    await conn.close();
  });
});
