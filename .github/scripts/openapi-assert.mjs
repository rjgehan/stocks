#!/usr/bin/env node
// OpenAPI contract assertions (ARCHITECTURE §18 rule 5, §26 "Contract"). Run by the CI `contract` job after
// `make openapi`:
//
//   node .github/scripts/openapi-assert.mjs [docs/openapi.json] [--allow-optional SchemaA,SchemaB]
//
// 1. No schema anywhere has `type: number` (also `type: ["number", "null"]`): the API has no floating-point
//    fields. Money is `type: string, format: decimal`; counts are `integer`.
// 2. Every property of every schema reachable from a response body is listed in that schema's `required`
//    (response DTOs always carry every field, nullable or not, §18.3). Request-only schemas are not checked.
//    `--allow-optional` names schemas exempt from rule 2 (agree the exception with WS1 first).
//
// No dependencies: plain Node 22.
import { readFileSync } from 'node:fs';

const args = process.argv.slice(2);
let file = 'docs/openapi.json';
const allowOptional = new Set();
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--allow-optional') {
    for (const name of (args[++i] ?? '').split(',')) if (name.trim()) allowOptional.add(name.trim());
  } else if (args[i] === '-h' || args[i] === '--help') {
    console.log('usage: openapi-assert.mjs [openapi.json] [--allow-optional SchemaA,SchemaB]');
    process.exit(0);
  } else {
    file = args[i];
  }
}

let doc;
try {
  doc = JSON.parse(readFileSync(file, 'utf8'));
} catch (e) {
  console.error(`openapi-assert: cannot read ${file}: ${e.message}`);
  process.exit(2);
}
if (typeof doc !== 'object' || doc === null || !String(doc.openapi ?? '').startsWith('3.')) {
  console.error(`openapi-assert: ${file} is not an OpenAPI 3.x document`);
  process.exit(2);
}

const esc = (s) => String(s).replace(/~/g, '~0').replace(/\//g, '~1');
const violations = [];

// ---- rule 1: no `type: number` -----------------------------------------------------------------------------
// Skip literal-value subtrees, where an object may legitimately contain a "type" key that is data, not schema.
const LITERAL_KEYS = new Set(['example', 'examples', 'default', 'enum', 'const']);
function walkNumbers(node, ptr) {
  if (Array.isArray(node)) {
    node.forEach((v, i) => walkNumbers(v, `${ptr}/${i}`));
    return;
  }
  if (node === null || typeof node !== 'object') return;
  const t = node.type;
  if (t === 'number' || (Array.isArray(t) && t.includes('number'))) {
    violations.push(`type: number at ${ptr || '/'}${node.format ? ` (format ${node.format})` : ''}`);
  }
  for (const [k, v] of Object.entries(node)) {
    if (LITERAL_KEYS.has(k)) continue;
    walkNumbers(v, `${ptr}/${esc(k)}`);
  }
}
walkNumbers(doc, '');

// ---- rule 2: response properties are required ----------------------------------------------------------------
function resolve(ref) {
  if (!ref.startsWith('#/')) throw new Error(`external $ref not supported: ${ref}`);
  let cur = doc;
  for (const raw of ref.slice(2).split('/')) {
    const key = raw.replace(/~1/g, '/').replace(/~0/g, '~');
    cur = cur?.[key];
  }
  if (cur === undefined) throw new Error(`unresolvable $ref: ${ref}`);
  return cur;
}
const schemaName = (ref) => (ref.startsWith('#/components/schemas/') ? ref.slice('#/components/schemas/'.length) : ref);

const visited = new Set();
let checkedSchemas = 0;
function visit(schema, label, inheritedRequired = new Set()) {
  if (schema === null || typeof schema !== 'object') return;
  if (typeof schema.$ref === 'string') {
    const key = `${schema.$ref}|${[...inheritedRequired].sort().join(',')}`;
    if (visited.has(key)) return;
    visited.add(key);
    visit(resolve(schema.$ref), schemaName(schema.$ref), inheritedRequired);
    return;
  }
  const required = new Set([...(Array.isArray(schema.required) ? schema.required : []), ...inheritedRequired]);
  if (schema.properties && typeof schema.properties === 'object') {
    checkedSchemas++;
    if (!allowOptional.has(label)) {
      for (const prop of Object.keys(schema.properties)) {
        if (!required.has(prop)) violations.push(`response property not required: ${label}.${prop}`);
      }
    }
    for (const [prop, sub] of Object.entries(schema.properties)) visit(sub, `${label}.${prop}`);
  }
  if (schema.items) visit(schema.items, `${label}[]`);
  if (Array.isArray(schema.prefixItems)) schema.prefixItems.forEach((s, i) => visit(s, `${label}[${i}]`));
  if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
    visit(schema.additionalProperties, `${label}{}`);
  }
  // allOf members share the composite's `required`; oneOf/anyOf alternatives stand alone.
  if (Array.isArray(schema.allOf)) schema.allOf.forEach((s) => visit(s, label, required));
  for (const k of ['oneOf', 'anyOf']) if (Array.isArray(schema[k])) schema[k].forEach((s) => visit(s, label));
}

const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'options', 'head', 'trace'];
let responseBodies = 0;
for (const [path, item] of Object.entries(doc.paths ?? {})) {
  for (const method of METHODS) {
    const op = item?.[method];
    if (!op) continue;
    for (const [status, resp0] of Object.entries(op.responses ?? {})) {
      const resp = resp0?.$ref ? resolve(resp0.$ref) : resp0;
      for (const [mediaType, media] of Object.entries(resp?.content ?? {})) {
        if (!media?.schema) continue;
        responseBodies++;
        const label = media.schema.$ref
          ? schemaName(media.schema.$ref)
          : `${method.toUpperCase()} ${path} ${status} ${mediaType}`;
        visit(media.schema, label);
      }
    }
  }
}

const unique = [...new Set(violations)];
if (unique.length) {
  console.error(`openapi-assert: ${file}: ${unique.length} violation(s)`);
  for (const v of unique) console.error(`  - ${v}`);
  console.error('Fix the DTO or OpenApiConfig (ARCHITECTURE §18 rules 1-5), then run `make openapi`.');
  process.exit(1);
}
console.log(
  `openapi-assert: ${file} OK (${Object.keys(doc.paths ?? {}).length} paths, ${responseBodies} response bodies, ` +
    `${checkedSchemas} object schemas checked; no type: number)`,
);
