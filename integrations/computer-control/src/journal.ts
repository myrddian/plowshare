import { decodeToolReceipt } from 'plowshare-client-ts';
import type { ToolJournal, ToolReceipt } from 'plowshare-client-ts';

/** Local result stores both text and content plus one admission receipt; reserve beyond wire size. */
const RESULT_RESERVE_BYTES = 262_144;
const FILE_LIMIT_BYTES = 2_000_000;

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
    const rows = (await delegate.all()).map(decodeToolReceipt);
    if (
      rows.length > 1000 ||
      new Set(rows.map((row) => row.invocationId)).size !== rows.length ||
      rows.some((row) => row.hostedWorker !== undefined)
    )
      throw new Error('Invalid computer journal');
    if (encodedBytes(delegate.identity, rows) > maxBytes)
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

  async save(value: ToolReceipt): Promise<void> {
    if (this.unavailable || this.saving)
      throw new Error('Computer journal is unavailable or busy');
    const next = decodeToolReceipt(value);
    const previous = this.rows.find(
      (row) => row.invocationId === next.invocationId,
    );
    transition(previous, next);
    const rows = [
      ...this.rows.filter((row) => row.invocationId !== next.invocationId),
      next,
    ];
    const reserved =
      rows.filter((row) => row.phase === 'executing').length *
      RESULT_RESERVE_BYTES;
    if (
      rows.length > 1000 ||
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
