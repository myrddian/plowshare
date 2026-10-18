import { describe, expect, it } from 'vitest';
import { decodeReply } from './schema.ts';
import { swarmSelectionCheck, swarmTypeCheck } from './swarm-types.ts';

const selection = {
  name: 'review',
  revision: 'a'.repeat(64),
  description: 'Review',
  members: ['critic'],
  budget: null,
};

describe('swarm allowance policy', () => {
  it('round trips an explicit unlimited policy through catalog and retained topics', () => {
    const type = { ...selection, refused: {}, origin: 'fixture', selection };
    expect(swarmSelectionCheck(selection)).toBe(true);
    expect(swarmTypeCheck(type)).toBe(true);
    expect(() =>
      decodeReply('swarm.types', { project: 'project', types: [type] }),
    ).not.toThrow();
  });

  it('still rejects omitted, fractional, negative and contradictory limits', () => {
    for (const budget of [undefined, -1, 0, 1, 2.5, 2147483648, 'unlimited'])
      expect(swarmSelectionCheck({ ...selection, budget })).toBe(false);
    expect(swarmSelectionCheck({ ...selection, budget: 12 })).toBe(true);
    expect(
      swarmTypeCheck({
        ...selection,
        budget: 12,
        refused: {},
        origin: 'fixture',
        selection,
      }),
    ).toBe(false);
  });
});
