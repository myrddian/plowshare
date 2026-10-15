import { readFile, open } from 'node:fs/promises';
import { join } from 'node:path';
import {
  atomicPrivateWrite,
  privateDirectory,
  privateFile,
} from 'plowshare-client-node/connections';
import { decodeToolCall } from 'plowshare-client-node/tools';
import type { ToolJournal, ToolReceipt, ToolResult } from 'plowshare-client-ts';
import { object, text } from './codec.ts';
import { unknownResult } from './contracts.ts';

/** Recover only while holding the journal's exclusive process/binding lock and after validating
 * its owner/contract. An executing receipt proves intent, not completion or absence of an effect.
 * Reuse the SDK's ready/UNKNOWN shape without publishing, consuming requests or inventing a new
 * execution. Original requests and worker fences remain untouched for owning reconciliation.
 */
export async function recoverInterrupted(journal: ToolJournal): Promise<void> {
  for (const receipt of await journal.all()) {
    if (receipt.phase === 'executing')
      await journal.save({
        ...receipt,
        phase: 'ready',
        result: unknownResult(),
        occurredAt: null,
      });
  }
}

function decodeReceipt(source: unknown): ToolReceipt {
  const raw = object(source);
  const invocationId = text(raw.invocationId, 36);
  if (typeof raw.request !== 'string')
    throw new Error('Invalid journal request');
  const request = raw.request;
  // The public decoder owns the complete envelope bound in UTF-16 units, not UTF-8 bytes.
  // A second byte ceiling here would reject valid Unicode calls before durable intent.
  if (decodeToolCall(request).invocationId !== invocationId)
    throw new Error('Conflicting journal invocation');
  const phase = raw.phase;
  if (
    phase !== 'executing' &&
    phase !== 'ready' &&
    phase !== 'publishing' &&
    phase !== 'done'
  )
    throw new Error('Invalid journal phase');
  let result: ToolResult | null = null;
  if (raw.result !== null) {
    const value = object(raw.result);
    const state = value.state;
    if (state !== 'COMPLETED' && state !== 'REJECTED' && state !== 'UNKNOWN')
      throw new Error('Invalid journal result');
    if (
      typeof value.text !== 'string' ||
      !value.text.trim() ||
      value.text.length > 16384
    )
      throw new Error('Invalid journal result text');
    result = { state, text: value.text };
  }
  const occurredAt = raw.occurredAt === null ? null : text(raw.occurredAt, 40);
  if (
    (phase === 'executing') !== (result === null) ||
    (phase === 'publishing' || phase === 'done') !== (occurredAt !== null) ||
    (occurredAt !== null && !Number.isFinite(Date.parse(occurredAt)))
  )
    throw new Error('Invalid journal lifecycle');
  const worker =
    raw.hostedWorker === undefined ? undefined : object(raw.hostedWorker);
  return {
    invocationId,
    request,
    phase,
    result,
    occurredAt,
    ...(worker === undefined
      ? {}
      : {
          hostedWorker: {
            binding: uuid(worker.binding),
            registration: uuid(worker.registration),
          },
        }),
  };
}
export function uuid(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
    throw new Error('Expected a UUID');
  return value;
}
async function readOptional(file: string): Promise<unknown> {
  await privateFile(file);
  try {
    const raw: unknown = JSON.parse(await readFile(file, 'utf8'));
    return raw;
  } catch (error) {
    if (error instanceof Error && 'code' in error && error.code === 'ENOENT')
      return null;
    throw new Error('MCP recovery state could not be read', { cause: error });
  }
}
export async function durablePrivateWrite(
  file: string,
  value: unknown,
): Promise<void> {
  await atomicPrivateWrite(file, value);
  // The SDK promises durable save, including the renamed directory entry.
  const directory = await open(join(file, '..'), 'r');
  try {
    await directory.sync();
  } finally {
    await directory.close();
  }
}
/** Owns only SDK receipts, under a caller-held binding lock. No upstream credential or session is saved. */
export class FileToolJournal implements ToolJournal {
  private constructor(
    readonly identity: string,
    private readonly file: string,
    private rows: readonly ToolReceipt[],
  ) {}
  static async open(
    directory: string,
    identity: string,
  ): Promise<FileToolJournal> {
    if (!/^[0-9a-f]{64}$/.test(identity))
      throw new Error('Invalid journal identity');
    await privateDirectory(directory);
    const file = join(directory, identity + '.json');
    const saved = await readOptional(file);
    let rows: readonly ToolReceipt[] = [];
    if (saved !== null) {
      const raw = object(saved);
      if (
        raw.identity !== identity ||
        !Array.isArray(raw.receipts) ||
        raw.receipts.length > 1000
      )
        throw new Error('Invalid journal identity or bound');
      const source: readonly unknown[] = raw.receipts;
      rows = source.map(decodeReceipt);
      if (new Set(rows.map((row) => row.invocationId)).size !== rows.length)
        throw new Error('Duplicate journal invocation');
    }
    return new FileToolJournal(identity, file, rows);
  }
  all(): Promise<readonly ToolReceipt[]> {
    return Promise.resolve(this.rows);
  }
  async save(receipt: ToolReceipt): Promise<void> {
    const validated = decodeReceipt(receipt);
    const next = [
      ...this.rows.filter((row) => row.invocationId !== validated.invocationId),
      validated,
    ];
    if (next.length > 1000)
      throw new Error(
        'MCP journal is full; archive settled receipts before continuing',
      );
    await durablePrivateWrite(this.file, {
      identity: this.identity,
      receipts: next,
    });
    this.rows = next;
  }
}
export interface CataloguePublicationIntent {
  begin(requestId: string, action: 'publish' | 'withdraw'): Promise<void>;
  settled(): Promise<void>;
}
/** Catalogue metadata has no public owning-receipt read. Retain its uncertainty and refuse automatic restart. */
export class CatalogueIntent implements CataloguePublicationIntent {
  private constructor(private readonly file: string) {}
  static async open(directory: string): Promise<CatalogueIntent> {
    await privateDirectory(directory);
    const file = join(directory, 'catalogue-intent.json');
    const saved = await readOptional(file);
    if (saved !== null && object(saved).pending !== null)
      throw new Error(
        'Uncertain catalogue publication requires operator reconciliation before restart',
      );
    return new CatalogueIntent(file);
  }
  async begin(
    requestId: string,
    action: 'publish' | 'withdraw',
  ): Promise<void> {
    await durablePrivateWrite(this.file, {
      pending: { requestId: uuid(requestId), action },
    });
  }
  async settled(): Promise<void> {
    await durablePrivateWrite(this.file, { pending: null });
  }
}

/** A new socket uses a fresh, durably retained registration UUID. An uncertain register is never retried. */
export class RegistrationIntent {
  private constructor(private readonly file: string) {}
  static async open(directory: string): Promise<RegistrationIntent> {
    await privateDirectory(directory);
    const file = join(directory, 'registration-intent.json');
    const saved = await readOptional(file);
    if (saved !== null && object(saved).pending !== null)
      throw new Error(
        'Uncertain worker registration requires operator reconciliation before restart',
      );
    return new RegistrationIntent(file);
  }
  async begin(requestId: string): Promise<void> {
    await durablePrivateWrite(this.file, { pending: uuid(requestId) });
  }
  async settled(): Promise<void> {
    await durablePrivateWrite(this.file, { pending: null });
  }
}
