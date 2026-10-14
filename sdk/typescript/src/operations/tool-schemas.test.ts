import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { fieldsOf } from './response.ts';
import {
  canonicalToolSchema,
  matchesToolSchemaFingerprint,
  decodeToolSchema,
  decodeToolValue,
  validateSchemaArguments,
  validateToolSchema,
} from './tool-schemas.ts';
import {
  decodeToolCall,
  toolDeploymentConfig,
  validateToolArguments,
} from './relay-tools.ts';
const fixture: unknown = JSON.parse(
  readFileSync(
    new URL(
      '../../../../test-support/contracts/tool-schemas.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
const cases: unknown = fieldsOf(fixture)['cases'];
if (!Array.isArray(cases)) throw new Error('Missing schema fixture cases');
describe('shared executable tool schema profile', () => {
  for (const entry of cases) {
    const row = fieldsOf(entry);
    it(String(row['name']), async () => {
      const schemaSource = row['schemaSource'],
        argsSource = row['argumentsSource'];
      if (typeof schemaSource !== 'string' || typeof argsSource !== 'string')
        throw new Error('Invalid fixture');
      const parsed: unknown = JSON.parse(schemaSource);
      if (row['validSchema'] !== true) {
        expect(() => validateToolSchema(decodeToolSchema(parsed))).toThrow();
        return;
      }
      const schema = validateToolSchema(decodeToolSchema(parsed));
      expect(schema).toEqual(parsed);
      const fingerprint = createHash('sha256')
        .update(canonicalToolSchema(schema))
        .digest('hex');
      expect(fingerprint).toBe(row['fingerprint']);
      const sha256 = async (text: string) =>
        createHash('sha256').update(text).digest('hex');
      expect(
        await matchesToolSchemaFingerprint(schema, fingerprint, sha256),
      ).toBe(true);
      expect(
        await matchesToolSchemaFingerprint(schema, '0'.repeat(64), sha256),
      ).toBe(false);
      const legacy = row['legacyFingerprint'];
      if (typeof legacy === 'string') {
        expect(legacy).not.toBe(fingerprint);
        expect(await matchesToolSchemaFingerprint(schema, legacy, sha256)).toBe(
          true,
        );
        expect(
          await matchesToolSchemaFingerprint(
            { ...schema, description: 'changed' },
            legacy,
            sha256,
          ),
        ).toBe(false);
      }
      const check = () => {
        const raw: unknown = JSON.parse(argsSource);
        validateSchemaArguments(schema, decodeToolValue(raw));
      };
      if (row['validArguments'] === true) check();
      else expect(check).toThrow();
    });
  }
  it('versioned nested declarations retain their schema and distinguish absent/null', () => {
    const schema = validateToolSchema({
      type: 'object',
      properties: {
        value: { type: ['array', 'null'], items: { type: 'integer' } },
      },
      required: ['value'],
      additionalProperties: false,
    });
    const declaration = {
      name: 'inspect',
      description: 'Inspect nested input',
      parameters: [],
      timeoutSeconds: 30,
      inputSchema: schema,
    };
    const config = fieldsOf(
      JSON.parse(
        toolDeploymentConfig(
          { project: 'fixture', provider: 'scanner', account: 'provider' },
          [declaration],
        ),
      ),
    );
    expect(JSON.stringify(config)).toContain('inputSchema');
    const request = {
      schema: 'plowshare-tool/2',
      invocationId: '11111111-1111-1111-1111-111111111111',
      project: 'fixture',
      provider: 'scanner',
      tool: 'inspect',
      account: 'caller',
      run: 'run',
      call: 'call',
      deadline: '2099-01-01T00:00:00Z',
      schemaFingerprint: createHash('sha256')
        .update(canonicalToolSchema(schema))
        .digest('hex'),
      arguments: { value: [1, 2] },
    };
    const call = decodeToolCall(JSON.stringify(request));
    validateToolArguments(declaration, call.arguments);
    expect(call.arguments).toEqual(request.arguments);
    expect(Object.isFrozen(call.arguments['value'])).toBe(true);
    expect(() =>
      decodeToolCall(
        JSON.stringify({ ...request, schema: 'plowshare-tool/1' }),
      ),
    ).toThrow();
    expect(() => validateToolArguments(declaration, {})).toThrow();
    validateToolArguments(declaration, { value: null });
  });
  it('refuses cycles, oversized collections and global depth before effects', () => {
    const cycle: Record<string, unknown> = {};
    cycle['self'] = cycle;
    expect(() => decodeToolValue(cycle)).toThrow();
    expect(() =>
      decodeToolValue(Array.from({ length: 1025 }, () => null)),
    ).toThrow();
    let value: unknown = null;
    for (let i = 0; i < 34; i++) value = [value];
    expect(() => decodeToolValue(value)).toThrow();
  });
});
