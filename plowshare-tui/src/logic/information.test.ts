import { describe, it, expect } from 'vitest';
import {
  InformationClient,
  informationChanged,
  informationCommand,
} from './information.ts';

describe('information over WebSocket', () => {
  it('pins the selected namespace and preserves the mutation receipt across retries', async () => {
    const sent: unknown[] = [];
    const client = new InformationClient(
      {
        ask: async (type, payload) => {
          sent.push([type, payload]);
          return {
            code: 'ACCEPTED',
            payload: {
              revision: 'retained',
              resource: 'resource',
              created: true,
            },
          };
        },
      },
      { kind: 'project', project: 'research', includeShared: true },
    );
    const payload = {
      name: 'source',
      text: 'Original bytes.',
      requestId: '00000000-0000-4000-8000-000000000001',
    };
    const forged = { ...payload, account: 'forged' };
    await expect(client.call('upload', forged)).rejects.toThrow();
    expect(sent).toHaveLength(0);
    expect(await client.call('upload', payload)).toEqual({
      revision: 'retained',
      resource: 'resource',
      created: true,
    });
    await client.call('upload', payload);
    expect(sent[0]).toEqual(sent[1]);
    expect(sent[0]).toEqual([
      'information.upload',
      {
        ...payload,
        scope: { kind: 'project', project: 'research', includeShared: true },
      },
    ]);
  });
  it('keeps refusal distinct from an empty catalogue', async () => {
    const client = new InformationClient(
      {
        ask: async () => ({
          code: 'NOT_FOUND',
          said: 'Inputs were withdrawn.',
        }),
      },
      { kind: 'personal' },
    );
    await expect(client.call('list')).rejects.toThrow('Inputs were withdrawn.');
  });
  it('accepts only valid invalidation hints and tolerates repeated delivery', () => {
    const event = {
      kind: 'information.changed',
      sequence: 4,
      revision: 'revision',
      generation: 2,
    };
    expect(informationChanged(event)).toEqual(event);
    expect(informationChanged({ ...event, sequence: -1 })).toBeUndefined();
    expect(informationChanged({ ...event, generation: 1.2 })).toBeUndefined();
    expect(informationChanged({ kind: 'job.ended' })).toBeUndefined();
  });
});

describe('information commands', () => {
  it('keeps JSON quotations and source whitespace intact and applies current project', () => {
    const parsed = informationCommand(
      'information upload {"name":"paper", "text":"He said \\"hello\\".\\nA second line.","requestId":"stable"}',
      'research',
    );
    expect(parsed.scope).toEqual({ kind: 'project', project: 'research' });
    if (parsed.operation !== 'upload') throw new Error('Expected upload.');
    expect(parsed.payload.text).toBe('He said "hello".\nA second line.');
    expect(parsed.payload.requestId).toBe('stable');
  });
  it('defaults to a bounded personal list and validates explicit scope and payload', () => {
    expect(informationCommand('information')).toEqual({
      operation: 'list',
      scope: { kind: 'personal' },
      payload: {},
    });
    expect(
      informationCommand('information inventory --scope personal', 'research')
        .scope,
    ).toEqual({ kind: 'personal' });
    expect(informationCommand('information list --scope shared').scope).toEqual(
      { kind: 'shared' },
    );
    expect(() =>
      informationCommand('information list --scope project'),
    ).toThrow('Select a project');
    expect(() => informationCommand('information nope')).toThrow('Operations:');
    expect(() => informationCommand('information read --owner bob {}')).toThrow(
      'Unknown information option',
    );
    expect(() => informationCommand('information read {bad}')).toThrow();
  });
});

it('accepts quoted project names in slash commands', () => {
  expect(
    informationCommand('information list --project "Research notes" {}').scope,
  ).toEqual({ kind: 'project', project: 'Research notes' });
});
