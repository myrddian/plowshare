import { performance } from 'node:perf_hooks';
import { decodeContract } from 'plowshare-client-ts/operations/schema';
import { decodeDesktopRequest } from '../src/request-boundary.ts';
import { DESKTOP_REQUEST_ACTIONS } from '../src/request-index.ts';
import { DESKTOP_SCHEMA } from '../src/request-schemas.ts';

// Run with node --experimental-strip-types scripts/request-decoding-benchmark.mjs.
// This measures synchronous decoding in isolation, not end-to-end Electron lag.
const generic = (value) =>
  decodeContract(
    DESKTOP_SCHEMA.request,
    DESKTOP_SCHEMA.$defs,
    value,
    'desktop request',
  );
const batches = 11,
  iterations = 100,
  warmup = 250;
function medianMicroseconds(decode, value) {
  for (let n = 0; n < warmup; n++) decode(value);
  const samples = [];
  for (let batch = 0; batch < batches; batch++) {
    const start = performance.now();
    for (let n = 0; n < iterations; n++) decode(value);
    samples.push(((performance.now() - start) * 1000) / iterations);
  }
  return samples.sort((a, b) => a - b)[Math.floor(samples.length / 2)];
}
function errorsConstructed(decode, value) {
  const OriginalError = globalThis.Error;
  let count = 0;
  try {
    globalThis.Error = class extends OriginalError {
      constructor(...args) {
        super(...args);
        count++;
      }
    };
    decode(value);
    return count;
  } finally {
    globalThis.Error = OriginalError;
  }
}
const timings = ['bootstrap', 'scope', 'provider-management-pending'].map(
  (action) => {
    const value = { action };
    return {
      action,
      genericMicroseconds: medianMicroseconds(generic, value),
      desktopMicroseconds: medianMicroseconds(decodeDesktopRequest, value),
    };
  },
);
// Count errors after timing so replacing Error cannot perturb the timed JIT warmup.
const sizes = [...DESKTOP_REQUEST_ACTIONS.values()]
  .map((schema) => schema.anyOf?.length ?? 0)
  .sort((a, b) => a - b);
console.log(
  JSON.stringify(
    {
      node: process.version,
      batches,
      iterations,
      warmup,
      index: {
        actions: DESKTOP_REQUEST_ACTIONS.size,
        reachableVariants: new Set(
          [...DESKTOP_REQUEST_ACTIONS.values()].flatMap(
            (schema) => schema.anyOf ?? [],
          ),
        ).size,
        minCandidates: sizes[0],
        medianCandidates: sizes[Math.floor(sizes.length / 2)],
        maxCandidates: sizes.at(-1),
      },
      results: timings.map((row) => ({
        ...row,
        candidates: DESKTOP_REQUEST_ACTIONS.get(row.action)?.anyOf?.length,
        genericErrors: errorsConstructed(generic, { action: row.action }),
        desktopErrors: errorsConstructed(decodeDesktopRequest, {
          action: row.action,
        }),
      })),
    },
    null,
    2,
  ),
);
