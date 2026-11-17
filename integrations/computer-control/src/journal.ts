import { decodeToolReceipt } from 'plowshare-client-ts';
import type { ToolJournal, ToolReceipt } from 'plowshare-client-ts';

/** Local result stores both text and content plus one admission receipt; reserve beyond wire size. */
const RESULT_RESERVE_BYTES = 262_144;
const FILE_LIMIT_BYTES = 2_000_000;
const MAX_RECEIPTS = 1000;
// READY still needs a timestamp and a longer phase name. Keep this reservation
// through PUBLISHING too: a proven NOT_SUBMITTED response may roll back to READY.
// The encoded timestamp bound below leaves room for every later checkpoint.
const PUBLICATION_SLOT_BYTES = 128;

export type CapacityToolJournalStatus =
  | {
      readonly state: 'AVAILABLE';
      readonly maximumBytes: number;
      readonly maximumReceipts: number;
      readonly receiptCount: number;
      readonly encodedBytes: number;
      readonly reservedBytes: number;
      readonly freeBytes: number;
      readonly phases: Readonly<Record<ToolReceipt['phase'], number>>;
    }
  | {
      readonly state: 'SAVING' | 'SAVE_UNCERTAIN';
      readonly maximumBytes: number;
      readonly maximumReceipts: number;
    };

function checkedReceipt(value: ToolReceipt): ToolReceipt {
  const checked = decodeToolReceipt(value);
  // Node's Date.toISOString is at most 27 bytes. Tolerate ordinary historical
  // UTC formats while bounding JSON escaping, not merely the source length.
  if (
    checked.occurredAt !== null &&
    Buffer.byteLength(JSON.stringify(checked.occurredAt), 'utf8') > 64
  )
    throw new Error('Computer journal publication timestamp exceeds its bound');
  return checked;
}

function reservedBytes(rows: readonly ToolReceipt[]): number {
  return rows.reduce(
    (bytes, row) =>
      bytes +
      (row.phase === 'executing' ? RESULT_RESERVE_BYTES : 0) +
      (row.phase === 'done'
        ? 0
        : PUBLICATION_SLOT_BYTES -
          Buffer.byteLength(
            JSON.stringify(row.phase) + JSON.stringify(row.occurredAt),
            'utf8',
          )),
    0,
  );
}

function encodedBytes(identity: string, rows: readonly ToolReceipt[]): number {
  return Buffer.byteLength(
    JSON.stringify({ identity, receipts: rows }) + '\n',
    'utf8',
  );
}
function sameResult(left: ToolReceipt, right: ToolReceipt): boolean {
  return JSON.stringify(left.result) === JSON.stringify(right.result);
}
function transition(
  previous: ToolReceipt | undefined,
  next: ToolReceipt,
): void {
  if (next.hostedWorker !== undefined)
    throw new Error('Hosted computer journals are unsupported');
  if (previous === undefined) {
    if (next.phase !== 'executing')
      throw new Error('A new receipt requires execution intent');
    return;
  }
  if (
    previous.request !== next.request ||
    previous.invocationId !== next.invocationId
  )
    throw new Error('An invocation request is immutable');
  if (previous.result !== null && !sameResult(previous, next))
    throw new Error('An invocation result is immutable');
  if (previous.phase === next.phase) {
    if (JSON.stringify(previous) !== JSON.stringify(next))
      throw new Error('A checkpoint is immutable');
    return;
  }
  const allowed =
    (previous.phase === 'executing' && next.phase === 'ready') ||
    (previous.phase === 'ready' && next.phase === 'publishing') ||
    (previous.phase === 'publishing' && next.phase === 'done') ||
    // Only the SDK's proven NOT_SUBMITTED publication path is allowed to request this transition.
    (previous.phase === 'publishing' && next.phase === 'ready');
  if (!allowed) throw new Error('Invalid computer journal transition');
  if (
    previous.phase === 'publishing' &&
    next.phase === 'done' &&
    previous.occurredAt !== next.occurredAt
  )
    throw new Error('Publication identity is immutable');
}

/**
 * SDK-only journal composition: reserve future result capacity before execution intent is durable.
 * The underlying private journal and its exclusive lifetime lock still own physical persistence.
 * An uncertain save fences this wrapper; reopening requires the original underlying journal.
 */
export class CapacityToolJournal implements ToolJournal {
  readonly identity: string;
  private rows: readonly ToolReceipt[];
  private unavailable = false;
  private saving = false;

  private constructor(
    private readonly delegate: ToolJournal,
    rows: readonly ToolReceipt[],
    private readonly maxBytes: number,
  ) {
    this.identity = delegate.identity;
    this.rows = rows;
  }

  static async create(
    delegate: ToolJournal,
    maxBytes = FILE_LIMIT_BYTES,
  ): Promise<CapacityToolJournal> {
    if (
      !/^[0-9a-f]{64}$/u.test(delegate.identity) ||
      !Number.isSafeInteger(maxBytes) ||
      maxBytes < 1024 ||
      maxBytes > FILE_LIMIT_BYTES
    )
      throw new Error('Invalid computer journal identity or capacity');
    const rows = (await delegate.all()).map(checkedReceipt);
    if (
      rows.length > MAX_RECEIPTS ||
      new Set(rows.map((row) => row.invocationId)).size !== rows.length ||
      rows.some((row) => row.hostedWorker !== undefined)
    )
      throw new Error('Invalid computer journal');
    if (encodedBytes(delegate.identity, rows) + reservedBytes(rows) > maxBytes)
      throw new Error('Computer journal exceeds capacity');
    return new CapacityToolJournal(delegate, Object.freeze(rows), maxBytes);
  }

  all(): Promise<readonly ToolReceipt[]> {
    if (this.unavailable)
      return Promise.reject(
        new Error(
          'Computer journal save was uncertain; reopen before recovery',
        ),
      );
    return Promise.resolve(Object.freeze(this.rows.map(decodeToolReceipt)));
  }

  /** Payload-free local occupancy. Unsettled writes never report stale capacity. */
  status(): CapacityToolJournalStatus {
    const limits = {
      maximumBytes: this.maxBytes,
      maximumReceipts: MAX_RECEIPTS,
    };
    if (this.unavailable || this.saving)
      return Object.freeze({
        ...limits,
        state: this.unavailable ? 'SAVE_UNCERTAIN' : 'SAVING',
      });
    const encoded = encodedBytes(this.identity, this.rows),
      reserved = reservedBytes(this.rows);
    const phases = { executing: 0, ready: 0, publishing: 0, done: 0 };
    for (const row of this.rows) phases[row.phase]++;
    return Object.freeze({
      ...limits,
      state: 'AVAILABLE',
      receiptCount: this.rows.length,
      encodedBytes: encoded,
      reservedBytes: reserved,
      freeBytes: this.maxBytes - encoded - reserved,
      phases: Object.freeze(phases),
    });
  }

  async save(value: ToolReceipt): Promise<void> {
    if (this.unavailable || this.saving)
      throw new Error('Computer journal is unavailable or busy');
    const next = checkedReceipt(value);
    const previous = this.rows.find(
      (row) => row.invocationId === next.invocationId,
    );
    transition(previous, next);
    const rows = [
      ...this.rows.filter((row) => row.invocationId !== next.invocationId),
      next,
    ];
    const reserved = reservedBytes(rows);
    if (
      rows.length > MAX_RECEIPTS ||
      encodedBytes(this.identity, rows) + reserved > this.maxBytes
    )
      throw new Error(
        'Computer journal capacity cannot cover the result lifecycle',
      );
    this.saving = true;
    try {
      await this.delegate.save(next);
      this.rows = Object.freeze(rows);
    } catch (error) {
      this.unavailable = true;
      throw error;
    } finally {
      this.saving = false;
    }
  }
}
