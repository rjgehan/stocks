import { describe, expect, it } from 'vitest';
import { functionTools, SAVE_NOTES, toAnthropicTools, toOpenAITools, toParameters, TOOL_NAME } from '../src/tools.js';
import { FIXTURE_TOOLS } from './fakes/mcp.js';

describe('MCP → function tools', () => {
  it('offers all 38 stocks tools in server order plus save_notes', () => {
    expect(FIXTURE_TOOLS).toHaveLength(38);
    const { tools, dropped } = functionTools(FIXTURE_TOOLS);
    expect(dropped).toEqual([]);
    expect(tools.map((t) => t.name)).toEqual([...FIXTURE_TOOLS.map((t) => t.name), SAVE_NOTES]);
    expect(tools[0]?.name).toBe('whoami');
    expect(tools[30]?.name).toBe('place_order');
    for (const t of tools) {
      expect(t.name).toMatch(TOOL_NAME);
      expect(t.description.length).toBeGreaterThan(10);
      expect(t.parameters.type).toBe('object');
      expect(t.parameters).not.toHaveProperty('$schema');
      expect(typeof t.parameters.properties).toBe('object');
    }
  });

  it('keeps the input schema itself (decimal strings or numbers, enums, required)', () => {
    const place = functionTools(FIXTURE_TOOLS).tools.find((t) => t.name === 'place_order')!;
    const props = place.parameters.properties as Record<string, Record<string, unknown>>;
    expect(props.qty?.type).toEqual(['string', 'number']);
    expect(props.side?.enum).toEqual(['BUY', 'SELL']);
    expect(place.parameters.required).toEqual(['client_order_id', 'symbol', 'side', 'type']);
  });

  it('gives an empty-input tool an empty properties object (OpenAI requires one)', () => {
    expect(toParameters({ $schema: 'x', type: 'object' })).toEqual({ type: 'object', properties: {} });
    expect(toParameters(undefined)).toEqual({ type: 'object', properties: {} });
  });

  it('drops names the vendors reject and a server tool that would shadow save_notes', () => {
    const { tools, dropped } = functionTools([
      { name: 'ok_tool', inputSchema: { type: 'object' } },
      { name: 'bad name', inputSchema: {} },
      { name: 'save_notes', inputSchema: {} },
      { name: 'ok_tool', inputSchema: {} },
    ]);
    expect(tools.map((t) => t.name)).toEqual(['ok_tool', 'save_notes']);
    expect(dropped).toEqual(['bad name', 'save_notes', 'ok_tool']);
    expect(tools[0]?.description).toBe('ok_tool');
  });

  it('converts to Anthropic custom tools and OpenAI Responses function tools', () => {
    const { tools } = functionTools(FIXTURE_TOOLS);
    const a = toAnthropicTools(tools);
    expect(a[0]).toEqual({ name: 'whoami', description: tools[0]?.description, input_schema: tools[0]?.parameters });
    const o = toOpenAITools(tools);
    expect(o.at(-1)).toMatchObject({ type: 'function', name: 'save_notes', strict: false });
    expect(o.at(-1)?.parameters).toMatchObject({ required: ['text'] });
  });

  it('does not share schema objects with the MCP tool list', () => {
    const src = { type: 'object', properties: { a: { type: 'string' } } };
    const out = toParameters(src);
    (out.properties as Record<string, unknown>).b = 1;
    expect(src.properties).not.toHaveProperty('b');
  });
});
