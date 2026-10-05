import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { VIEWS } from './shell';

/** Console view additions must update the reviewed public navigation contract. */
describe('the console is a declared front end', () => {
  it('matches the reviewed console view contract in both directions', () => {
    const contract = JSON.parse(
      readFileSync(
        join(
          dirname(fileURLToPath(import.meta.url)),
          '../../../test-support/contracts/console-views.json',
        ),
        'utf8',
      ),
    ) as { views: string[] };
    expect(contract.views.length).toBeGreaterThan(0);
    expect([...VIEWS].sort()).toEqual(contract.views);
  });
});
