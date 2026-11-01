import { lstat, open, readFile } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import {
  atomicPrivateWrite,
  privateDirectory,
  privateFile,
} from './connections.ts';
import {
  admitInvocationImage,
  invocationImageRequest,
} from './image-admissions.ts';
import {
  decodeToolCall,
  decodeToolReceipt,
  decodeToolResult,
} from 'plowshare-client-ts';
import {
  decodeToolContent,
  toolContentText,
  toolImagePart,
} from 'plowshare-client-ts/operations/tool-content';
import type {
  ToolCall,
  ToolJournal,
  ToolReceipt,
  ToolResult,
} from 'plowshare-client-ts';
import type {
  ToolContentPart,
  ToolImageUse,
} from 'plowshare-client-ts/operations/tool-content';
import type { ImageAdmissionReceipt } from 'plowshare-client-ts/operations/image-admissions';
import { createHash } from 'node:crypto';

/** Explicit capture upload; uncertain completion must be reconciled by its original admission slot. */
export async function admitToolImage(
  origin: string,
  bearer: string,
  call: ToolCall,
  ordinal: number,
  bytes: Uint8Array,
  use: ToolImageUse,
  http: typeof fetch = fetch,
): Promise<{
  readonly part: ToolContentPart;
  readonly receipt: ImageAdmissionReceipt;
}> {
  call = decodeToolCall(JSON.stringify(call));
  if (use !== 'OBSERVATION' && use !== 'OUTPUT' && use !== 'BOTH')
    throw new Error('Invalid image use');
  if (call.schema !== 'plowshare-tool/3' || call.resultProfile !== 'content/1')
    throw new Error('Image tool results were not negotiated');
  const snapshot = new Uint8Array(bytes);
  const hash = createHash('sha256').update(snapshot).digest('hex');
  const request = await invocationImageRequest(call.invocationId, ordinal);
  const receipt = await admitInvocationImage(
    origin,
    bearer,
    request,
    snapshot,
    'capture',
    http,
  );
  if (
    receipt.project !== call.project ||
    receipt.owner !== call.account ||
    receipt.sha256 !== hash ||
    receipt.byteLength !== snapshot.length
  )
    throw new Error('Foreign image admission receipt');
  return Object.freeze({ part: toolImagePart(receipt, use), receipt });
}

/** Ordered content plus local immutable receipt evidence, saved by ToolProvider before publishing. */
export function imageToolResult(
  call: ToolCall,
  parts: readonly ToolContentPart[],
  admissions: readonly ImageAdmissionReceipt[],
): ToolResult {
  call = decodeToolCall(JSON.stringify(call));
  if (call.resultProfile !== 'content/1')
    throw new Error('Image tool results were not negotiated');
  const content = decodeToolContent({ version: 'content/1', parts });
  return decodeToolResult(
    { state: 'COMPLETED', text: toolContentText(content), content, admissions },
    call,
  );
}

/**
 * Private bounded adapter for the existing provider journal. The caller holds an exclusive
 * binding/process lock for its lifetime. No bytes or bearer are retained in this JSON file.
 * Fail-fast saves protect against accidentally sharing the same instance across providers.
 */
export class FileToolJournal implements ToolJournal {
  private saving = false;
  private unavailable = false;
  readonly identity: string;
  private readonly file: string;
  private rows: readonly ToolReceipt[];
  private constructor(
    identity: string,
    file: string,
    rows: readonly ToolReceipt[],
  ) {
    this.identity = identity;
    this.file = file;
    this.rows = rows;
  }
  static async open(
    directory: string,
    identity: string,
  ): Promise<FileToolJournal> {
    if (!isAbsolute(directory) || !/^[0-9a-f]{64}$/.test(identity))
      throw new Error('Invalid tool journal location or identity');
    await privateDirectory(directory);
    const file = join(directory, identity + '.json');
    await privateFile(file);
    let rows: readonly ToolReceipt[] = [];
    try {
      if ((await lstat(file)).size > 2_000_000)
        throw new Error('Tool journal exceeds its byte bound');
      const saved: unknown = JSON.parse(await readFile(file, 'utf8'));
      if (
        saved === null ||
        typeof saved !== 'object' ||
        !('identity' in saved) ||
        saved.identity !== identity ||
        !('receipts' in saved) ||
        !Array.isArray(saved.receipts) ||
        saved.receipts.length > 1000
      )
        throw new Error('Invalid tool journal');
      const values: readonly unknown[] = saved.receipts;
      rows = Object.freeze(values.map(decodeToolReceipt));
      if (new Set(rows.map((r) => r.invocationId)).size !== rows.length)
        throw new Error('Duplicate tool journal invocation');
    } catch (error) {
      if (!(
        error instanceof Error &&
        'code' in error &&
        error.code === 'ENOENT'
      ))
        throw error;
    }
    return new FileToolJournal(identity, file, rows);
  }
  all(): Promise<readonly ToolReceipt[]> {
    if (this.unavailable)
      return Promise.reject(
        new Error('Tool journal save was uncertain; reopen before recovery'),
      );
    return Promise.resolve(this.rows);
  }
  async save(receipt: ToolReceipt): Promise<void> {
    if (this.unavailable)
      throw new Error(
        'Tool journal save was uncertain; reopen before recovery',
      );
    if (this.saving) throw new Error('Tool journal already has an active save');
    this.saving = true;
    try {
      const checked = decodeToolReceipt(receipt);
      const next = Object.freeze([
        ...this.rows.filter((r) => r.invocationId !== checked.invocationId),
        checked,
      ]);
      if (next.length > 1000)
        throw new Error('Tool journal is full; archive settled receipts');
      await atomicPrivateWrite(this.file, {
        identity: this.identity,
        receipts: next,
      });
      const parent = await open(join(this.file, '..'), 'r');
      try {
        await parent.sync();
      } finally {
        await parent.close();
      }
      this.rows = next;
    } catch (error) {
      // A rename/force failure cannot prove which checkpoint survived. Continuing from the old
      // in-memory snapshot could overwrite a committed result with UNKNOWN; reopen to reconcile.
      this.unavailable = true;
      throw error;
    } finally {
      this.saving = false;
    }
  }
}

export {
  toolTextPart,
  toolImagePart,
  toolPngPart,
} from 'plowshare-client-ts/operations/tool-content';
export type {
  ToolContentBody,
  ToolContentPart,
  ToolImageUse,
} from 'plowshare-client-ts/operations/tool-content';

/** Compatibility helper: reject non-PNG bytes before upload. */
export function admitToolPng(
  origin: string,
  bearer: string,
  call: ToolCall,
  ordinal: number,
  bytes: Uint8Array,
  use: ToolImageUse,
  http: typeof fetch = fetch,
): ReturnType<typeof admitToolImage> {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (!signature.every((value, index) => bytes[index] === value))
    return Promise.reject(new Error('PNG bytes required'));
  return admitToolImage(origin, bearer, call, ordinal, bytes, use, http);
}

/** Compatibility helper with its original PNG-only receipt contract. */
export function pngToolResult(
  call: ToolCall,
  parts: readonly ToolContentPart[],
  admissions: readonly ImageAdmissionReceipt[],
): ToolResult {
  if (admissions.some((receipt) => receipt.mimeType !== 'image/png'))
    throw new Error('PNG receipt required');
  return imageToolResult(call, parts, admissions);
}
