import { describe, expect, it } from 'vitest';
import { decodePayload } from './schema.ts';
import { commandProblem } from './payload-validation.ts';

describe.each(['schedule.list', 'schedule.files'] as const)(
  '%s pagination boundary',
  (operation) => {
    it('preserves opaque exclusive cursors and optional project selection', () => {
      for (const payload of [
        {},
        { limit: 1 },
        { after: 'scheduled-10-nightly', limit: 100 },
        { project: 'application', after: 'daily', limit: 51 },
      ]) {
        expect(commandProblem(operation, payload)).toBeUndefined();
        expect(decodePayload(operation, payload)).toEqual(payload);
      }
    });

    it('rejects malformed paging instead of coercing or trimming it', () => {
      for (const payload of [
        { after: '' },
        { after: ' daily' },
        { after: 'daily ' },
        { after: 'bad\u0000cursor' },
        { after: 'bad\u0085cursor' },
        { after: 'x'.repeat(513) },
        { after: 1 },
        { after: null },
        { limit: 0 },
        { limit: 101 },
        { limit: 1.5 },
        { limit: '50' },
        { limit: null },
        { limit: Number.NaN },
        { offset: 50 },
        { project: null },
        { project: 'personal:other' },
      ]) {
        expect(commandProblem(operation, payload)).toBeDefined();
        expect(() => decodePayload(operation, payload)).toThrow();
      }
    });
  },
);
