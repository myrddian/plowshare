import { describe, expect, it } from 'vitest';
import { schedulePages, SCHEDULE_PAGE_LIMIT } from './schedule-pages.ts';

describe('bounded schedule page traversal', () => {
  const first = Array.from({ length: SCHEDULE_PAGE_LIMIT }, (_, index) => ({
    name: `file-${String(index).padStart(3, '0')}`,
  }));
  it('uses the last unique key and retains a managed file after page one', async () => {
    const cursors: (string | undefined)[] = [];
    const found: string[] = [];
    for await (const page of schedulePages(
      (after) => {
        cursors.push(after);
        return Promise.resolve(
          after === undefined ? first : [{ name: 'managed-later' }],
        );
      },
      (row) => row.name,
    ))
      found.push(...page.map((row) => row.name));
    expect(cursors).toEqual([undefined, 'file-099']);
    expect(found.at(-1)).toBe('managed-later');
  });
  it('stops target lookup without requesting unrelated later pages', async () => {
    let calls = 0;
    for await (const page of schedulePages(
      () => {
        calls++;
        return Promise.resolve(first);
      },
      (row) => row.name,
    )) {
      if (page.some((row) => row.name === 'file-010')) break;
    }
    expect(calls).toBe(1);
  });
  it('refuses repeated cursors and oversized responses instead of declaring absence', async () => {
    await expect(
      (async () => {
        for await (const page of schedulePages(
          () => Promise.resolve(first),
          (row) => row.name,
        ))
          void page;
      })(),
    ).rejects.toThrow('did not advance');
    await expect(
      (async () => {
        for await (const page of schedulePages(
          () => Promise.resolve([...first, { name: 'extra' }]),
          (row) => row.name,
        ))
          void page;
      })(),
    ).rejects.toThrow('oversized');
  });
  it('propagates refusal of a later page so no partial listing proves absence', async () => {
    await expect(
      (async () => {
        for await (const page of schedulePages(
          (after) =>
            after === undefined
              ? Promise.resolve(first)
              : Promise.reject(new Error('account scope refused')),
          (row) => row.name,
        ))
          void page;
      })(),
    ).rejects.toThrow('account scope refused');
  });
});

it('rejects duplicated non-final keys before yielding a damaged page', async () => {
  for (const duplicateAcrossPages of [false, true]) {
    let yielded = 0;
    await expect(
      (async () => {
        for await (const page of schedulePages(
          (after) =>
            Promise.resolve(
              after === undefined && duplicateAcrossPages
                ? Array.from({ length: 100 }, (_, index) => ({
                    name: `first-${index}`,
                  }))
                : [
                    { name: duplicateAcrossPages ? 'first-20' : 'duplicate' },
                    { name: 'duplicate' },
                  ],
            ),
          (row) => row.name,
        )) {
          yielded += page.length;
        }
      })(),
    ).rejects.toThrow('unique valid keys');
    expect(yielded).toBe(duplicateAcrossPages ? 100 : 0);
  }
});
it('refuses blank and oversized keys without normalizing or yielding them', async () => {
  for (const name of [' ', 'x'.repeat(513)]) {
    const read = schedulePages(
      () => Promise.resolve([{ name }]),
      (row) => row.name,
    );
    await expect(read.next()).rejects.toThrow('unique valid keys');
  }
});
it('the complete traversal never retains more than 100000 unique keys', async () => {
  let pageNumber = 0;
  const read = schedulePages(
    () => {
      const page = pageNumber++;
      return Promise.resolve(
        page < 1000
          ? Array.from({ length: 100 }, (_, index) => ({
              name: `key-${page * 100 + index}`,
            }))
          : [{ name: 'one-too-many' }],
      );
    },
    (row) => row.name,
  );
  let yielded = 0;
  await expect(
    (async () => {
      for await (const page of read) yielded += page.length;
    })(),
  ).rejects.toThrow('bounded key budget');
  expect(yielded).toBe(100000);
});
