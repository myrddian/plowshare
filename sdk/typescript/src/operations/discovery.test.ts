import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CLI_OPERATIONS } from './catalog.ts';
import { MEMORY_OPERATIONS } from './direct.ts';
import { parseCommand } from './commands.ts';
import { discovery, commandHelp } from './discovery.ts';

describe('offline command discovery', () => {
  it('generates current schemas from the canonical request and reply types', () => {
    execFileSync(process.execPath, [
      fileURLToPath(
        new URL(
          '../../scripts/generate-operation-schemas.mjs',
          import.meta.url,
        ),
      ),
      '--check',
    ]);
  });
  it('covers every ordinary command in prose and machine-readable help', () => {
    const help = discovery(),
      names = help.commands.map((row) => row.command);
    for (const name of [
      ...Object.keys(CLI_OPERATIONS),
      ...Object.keys(MEMORY_OPERATIONS).map((verb) => 'memory ' + verb),
      'search',
      'job status',
      'job cancel',
    ]) {
      expect(names).toContain(name);
      expect(commandHelp()).toContain(name);
      const row = help.commands.find((row) => row.command === name)!;
      expect(row.input, name).toBeDefined();
      expect(row.result, name).toBeDefined();
    }
  });
  it('agrees with validation and canonical schemas on required web paging', () => {
    const row = discovery().commands.find(
      (row) => row.command === 'web search',
    )!;
    expect(row.required).toEqual(['query', 'pageSize', 'max', 'page']);
    expect(row.optional).toEqual([]);
    expect(row.input?.['required']).toEqual(row.required);
    expect(row.pagination).toEqual(['pageSize', 'max', 'page']);
    expect(parseCommand(`web search ${JSON.stringify(row.example)}`).kind).toBe(
      'request',
    );
    for (const field of row.pagination) {
      const payload = { ...(row.example as Record<string, unknown>) };
      delete payload[field];
      expect(parseCommand(`web search ${JSON.stringify(payload)}`).kind).toBe(
        'usage',
      );
      expect(
        parseCommand(
          `web search ${JSON.stringify({ ...(row.example as Record<string, unknown>), [field]: 0 })}`,
        ).kind,
      ).toBe('usage');
    }
  });
  it.each([
    'information acquire',
    'agent run',
    'orchestration start',
    'admin account create',
    'admin account update',
    'admin account reset',
    'admin session revoke',
    'admin service account create',
    'admin service account update',
    'admin service token create',
    'admin service token rotate',
    'admin service token revoke',
  ])('discovers usable %s fields, example, scope and results', (command) => {
    const row = discovery().commands.find((row) => row.command === command)!;
    expect(parseCommand(`${command} ${JSON.stringify(row.example)}`).kind).toBe(
      'request',
    );
    expect(row.mutation).toBe(true);
    expect(row.required?.length).toBeGreaterThan(0);
    expect(row.scope).toContain('account');
    expect(row.result).toBeDefined();
  });
});
