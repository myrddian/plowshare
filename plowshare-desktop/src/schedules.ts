import {
  definitionFromProposal,
  type ScheduleDefinition,
  type ScheduleFile,
} from 'plowshare-client-ts/operations/schedule-files';
import {
  request,
  resultOf,
  type Request as WsRequest,
} from 'plowshare-client-ts/operations/direct';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type {
  ScheduleProposal,
  ScheduleRecord,
  TriggerRecord,
  FiringRecord,
} from 'plowshare-client-ts/operations/administrative-replies';
import type { DesktopState } from './shared.ts';

const problem = (error: unknown) =>
  error instanceof Error ? error.message : 'Scheduled work is unavailable.';
export const scheduleIdentity = (
  value: ScheduleRecord | TriggerRecord | ScheduleProposal,
) => JSON.stringify(value);
export class ScheduleClient {
  private epoch = 0;
  private reading = 0;
  private previewing = 0;
  private busy = false;
  private state: () => DesktopState;
  private send: (ask: WsRequest) => Promise<Outcome>;
  private emit: () => void;
  constructor(
    state: ScheduleClient['state'],
    send: ScheduleClient['send'],
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  private get value() {
    return (this.state().activity.schedules ??= {
      schedules: [],
      triggers: [],
      firings: [],
    });
  }
  reset() {
    this.epoch++;
    this.reading++;
    this.previewing++;
    this.busy = false;
    const value = this.value;
    value.loading = false;
    value.previewing = false;
    value.busy = false;
    delete value.proposal;
  }
  private live() {
    if (!this.state().connected || this.state().mode !== 'live')
      throw new Error('Connect before managing scheduled work.');
  }
  private async checked(ask: WsRequest) {
    const answer = await this.send(ask),
      result = resultOf(ask, answer);
    if (result.kind === 'refused')
      throw new Error(answer.said ?? `The server refused ${ask.type}.`);
    if (result.kind === 'invalid-response')
      throw new Error(
        `The server returned incomplete ${ask.type} information.`,
      );
    return answer;
  }
  async refresh() {
    this.live();
    const epoch = this.epoch,
      revision = ++this.reading,
      value = this.value;
    value.loading = true;
    this.emit();
    try {
      const [schedules, triggers, firings, files] = await Promise.all([
        this.checked(request('schedule.list', {})),
        this.checked(request('trigger.list', {})),
        this.checked(request('firing.list', { limit: 100 })),
        this.checked(request('schedule.files', {})),
      ]);
      if (epoch !== this.epoch || revision !== this.reading) return;
      Object.assign(value, {
        schedules: schedules.payload as ScheduleRecord[],
        triggers: triggers.payload as TriggerRecord[],
        firings: firings.payload as FiringRecord[],
        files: files.payload as ScheduleFile[],
      });
      delete value.error;
    } catch (error) {
      if (epoch === this.epoch && revision === this.reading)
        value.error = problem(error);
    } finally {
      if (epoch === this.epoch && revision === this.reading) {
        value.loading = false;
        this.emit();
      }
    }
  }
  async preview(
    text: string,
    zone: string,
    project?: string,
    conversation?: string,
  ) {
    this.live();
    if (this.busy)
      throw new Error(
        'Wait for the scheduled-work change before reading another proposal.',
      );
    if (
      typeof text !== 'string' ||
      !text.trim() ||
      text.length > 16_000 ||
      typeof zone !== 'string' ||
      !zone.trim() ||
      zone.length > 128
    )
      throw new Error('Enter a schedule description and time zone.');
    if (
      project !== undefined &&
      !this.state().projects.some((row) => row.name === project)
    )
      throw new Error('Choose an available project.');
    if (
      conversation !== undefined &&
      !this.state().conversations.some((row) => row.id === conversation)
    )
      throw new Error('Choose an available conversation.');
    if (project && conversation)
      throw new Error('Choose a project or a conversation destination.');
    const epoch = this.epoch,
      revision = ++this.previewing,
      value = this.value;
    value.previewing = true;
    delete value.proposal;
    delete value.previewError;
    this.emit();
    try {
      const answer = await this.checked(
        request('schedule.read', {
          text,
          zone,
          ...(project ? { project } : {}),
          ...(conversation ? { conversation } : {}),
        }),
      );
      if (epoch !== this.epoch || revision !== this.previewing) return;
      const proposal = answer.payload as ScheduleProposal;
      if (
        proposal.project !== (project ?? null) ||
        proposal.conversation !== (conversation ?? null)
      )
        throw new Error(
          'The proposal names a different destination. Read a new proposal for the intended scope.',
        );
      const sourceProject =
        project ??
        this.state().conversations.find((row) => row.id === conversation)
          ?.project;
      if (sourceProject === undefined) delete value.sourceProject;
      else value.sourceProject = sourceProject;
      value.proposal = proposal;
    } catch (error) {
      if (epoch === this.epoch && revision === this.previewing) {
        value.previewError = problem(error);
        throw error;
      }
    } finally {
      if (epoch === this.epoch && revision === this.previewing) {
        value.previewing = false;
        this.emit();
      }
    }
  }
  async save(
    identity: string,
    definition?: ScheduleDefinition,
    source: 'server' | 'workspace' = 'server',
    project?: string,
  ) {
    this.live();
    const proposal = this.value.proposal;
    if (
      !proposal ||
      typeof identity !== 'string' ||
      scheduleIdentity(proposal) !== identity
    )
      throw new Error('Review the current proposal before saving.');
    await this.saveFile(
      proposal.names.schedule,
      definition ?? definitionFromProposal(proposal),
      source,
      project ?? this.value.sourceProject,
      false,
    );
    delete this.value.proposal;
  }
  async saveFile(
    name: string,
    definition: ScheduleDefinition,
    source: 'server' | 'workspace',
    project?: string,
    overwrite = false,
    identity?: string,
  ) {
    await this.mutate(async (guard) => {
      guard();
      if (overwrite) {
        const shown = this.value.files?.find(
          (file) =>
            file.name === name &&
            file.source === source &&
            file.project === (project ?? null),
        );
        if (!shown || identity !== JSON.stringify(shown))
          throw new Error('Choose a displayed schedule file before editing.');
        const files = (await this.checked(request('schedule.files', {})))
          .payload as ScheduleFile[];
        guard();
        const fresh = files.find(
          (file) => file.internalName === shown.internalName,
        );
        if (!fresh || JSON.stringify(fresh) !== identity)
          throw new Error(
            'This schedule file changed elsewhere. Refresh before saving.',
          );
      }
      const answer = await this.checked(
        request('schedule.save', {
          name,
          project: project ?? null,
          source,
          definition,
          overwrite,
        }),
      );
      const file = answer.payload as ScheduleFile;
      if (file.status !== 'active')
        throw new Error(
          file.error ?? 'The file was saved but its schedule was refused.',
        );
      return `Saved ${file.path}. The server is monitoring this folder.`;
    });
  }
  async sync(source: 'server' | 'workspace', project?: string) {
    await this.mutate(async (guard) => {
      guard();
      await this.checked(
        request('schedule.sync', { source, project: project ?? null }),
      );
      return 'Schedule folder registered and reconciled.';
    });
  }
  async change(
    kind: 'schedule' | 'trigger',
    name: string,
    identity: string,
    paused?: boolean,
  ) {
    this.live();
    if (kind !== 'schedule' && kind !== 'trigger')
      throw new Error('Choose a schedule or trigger.');
    const shown = (
      kind === 'schedule' ? this.value.schedules : this.value.triggers
    ).find((row) => row.name === name);
    if (
      !shown ||
      scheduleIdentity(shown) !== identity ||
      (paused !== undefined && typeof paused !== 'boolean')
    )
      throw new Error('Choose a displayed schedule or trigger.');
    await this.mutate(async (guard) => {
      const answer = await this.checked(
        kind === 'schedule'
          ? request('schedule.list', {})
          : request('trigger.list', {}),
      );
      guard();
      const current = (
        answer.payload as (ScheduleRecord | TriggerRecord)[]
      ).find((row) => row.name === name);
      if (!current || scheduleIdentity(current) !== identity)
        throw new Error(
          'This item changed elsewhere. Refresh before making a decision.',
        );
      await this.checked(
        kind === 'schedule'
          ? paused === undefined
            ? request('schedule.forget', { schedule: name })
            : request('schedule.pause', { schedule: name, paused })
          : paused === undefined
            ? request('trigger.forget', { trigger: name })
            : request('trigger.pause', { trigger: name, paused }),
      );
      return `${kind === 'schedule' ? 'Schedule' : 'Trigger'} ${paused === undefined ? 'removed' : paused ? 'paused' : 'resumed'}.`;
    });
  }
  async fire(trigger: string, identity: string) {
    this.live();
    const shown = this.value.triggers.find((row) => row.name === trigger);
    if (!shown || scheduleIdentity(shown) !== identity)
      throw new Error('Choose a displayed trigger.');
    await this.mutate(async (guard) => {
      const current = (
        (await this.checked(request('trigger.list', {})))
          .payload as TriggerRecord[]
      ).find((row) => row.name === trigger);
      guard();
      if (!current || scheduleIdentity(current) !== identity)
        throw new Error(
          'This trigger changed elsewhere. Refresh before firing its event.',
        );
      const answer = await this.checked(
        request('event.fire', { event: current.event }),
      );
      return `Event emitted; ${(answer.payload as FiringRecord[]).length} firings returned.`;
    });
  }
  private async mutate(work: (guard: () => void) => Promise<string>) {
    if (this.busy)
      throw new Error('Another scheduled-work change is being sent.');
    const epoch = this.epoch,
      value = this.value;
    this.busy = true;
    value.busy = true;
    delete value.error;
    delete value.notice;
    this.emit();
    const guard = () => {
      if (epoch !== this.epoch)
        throw new Error('The connection changed. Refresh before retrying.');
      this.live();
    };
    try {
      guard();
      const notice = await work(guard);
      guard();
      value.notice = notice;
      await this.refresh();
    } catch (error) {
      if (epoch === this.epoch)
        value.error = `${problem(error)} The request will not be replayed. Refresh to inspect what was saved.`;
      throw error;
    } finally {
      if (epoch === this.epoch) {
        this.busy = false;
        value.busy = false;
        this.emit();
      }
    }
  }
}
