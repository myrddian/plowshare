import type { OperationTransport } from '../../../sdk/typescript/src/operations/response.ts';
import type { ScheduleRecord } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import type { ScheduleFile } from '../../../sdk/typescript/src/operations/schedule-files.ts';
import { succeeded } from '../../../sdk/typescript/src/binding/codes.ts';
import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';

/** Account-owned runtime controls; never write a release or start an agent. */
export interface ScheduleControls {
  list(after?: string): Promise<readonly ScheduleRecord[]>;
  files(after?: string): Promise<readonly ScheduleFile[]>;
  pause(schedule: string, paused: boolean): Promise<void>;
}

export class ScheduleControlRefused extends Error {}

/** The tab's validated SDK socket owns transport; refusals are distinct from uncertainty. */
export class SdkScheduleControls implements ScheduleControls {
  constructor(private readonly transport: OperationTransport) {}

  async list(after?: string): Promise<readonly ScheduleRecord[]> {
    const reply = await this.transport.ask('schedule.list', {
      limit: 51,
      ...(after === undefined ? {} : { after }),
    });
    if (!succeeded(reply.code))
      throw new ScheduleControlRefused(
        reply.said ?? 'Schedule listing refused.',
      );
    return schedulePage(
      decodeReply('schedule.list', reply.payload),
      (row) => row.name,
      after,
    );
  }

  async files(after?: string): Promise<readonly ScheduleFile[]> {
    const reply = await this.transport.ask('schedule.files', {
      limit: 51,
      ...(after === undefined ? {} : { after }),
    });
    if (!succeeded(reply.code))
      throw new ScheduleControlRefused(
        reply.said ?? 'Schedule source listing refused.',
      );
    return schedulePage(
      decodeReply('schedule.files', reply.payload),
      (row) => row.internalName,
      after,
    );
  }

  async pause(schedule: string, paused: boolean): Promise<void> {
    const reply = await this.transport.ask('schedule.pause', {
      schedule,
      paused,
    });
    if (!succeeded(reply.code))
      throw new ScheduleControlRefused(
        reply.said ?? 'Schedule control refused.',
      );
  }
}

/** Ordering belongs to the database collation; validate bounded, unique cursor identities only. */
function schedulePage<T>(
  rows: readonly T[],
  key: (row: T) => string,
  after?: string,
): readonly T[] {
  if (rows.length > 51)
    throw new Error('The server returned an oversized schedule page.');
  const seen = new Set<string>();
  for (const row of rows) {
    const name = key(row);
    if (!name.trim() || name.length > 512 || name === after || seen.has(name))
      throw new Error(
        'The server returned an invalid or repeated schedule page key.',
      );
    seen.add(name);
  }
  return rows;
}
