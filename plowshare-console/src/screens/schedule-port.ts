import type { OperationTransport } from '../../../sdk/typescript/src/operations/response.ts';
import type { ScheduleRecord } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import type { ScheduleFile } from '../../../sdk/typescript/src/operations/schedule-files.ts';
import { succeeded } from '../../../sdk/typescript/src/binding/codes.ts';
import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';

/** Account-owned runtime controls; never write a release or start an agent. */
export interface ScheduleControls {
  list(): Promise<readonly ScheduleRecord[]>;
  files(): Promise<readonly ScheduleFile[]>;
  pause(schedule: string, paused: boolean): Promise<void>;
}

export class ScheduleControlRefused extends Error {}

/** The tab's validated SDK socket owns transport; refusals are distinct from uncertainty. */
export class SdkScheduleControls implements ScheduleControls {
  constructor(private readonly transport: OperationTransport) {}

  async list(): Promise<readonly ScheduleRecord[]> {
    const reply = await this.transport.ask('schedule.list', {});
    if (!succeeded(reply.code))
      throw new ScheduleControlRefused(
        reply.said ?? 'Schedule listing refused.',
      );
    return decodeReply('schedule.list', reply.payload);
  }

  async files(): Promise<readonly ScheduleFile[]> {
    const reply = await this.transport.ask('schedule.files', {});
    if (!succeeded(reply.code))
      throw new ScheduleControlRefused(
        reply.said ?? 'Schedule source listing refused.',
      );
    return decodeReply('schedule.files', reply.payload);
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
