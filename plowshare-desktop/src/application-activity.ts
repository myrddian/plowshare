import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Ask } from 'plowshare-client-ts/operations/client-views';
import type { CheckedAnswer } from 'plowshare-client-ts/operations/response';
import type { ProcConversation } from 'plowshare-client-ts/operations/proc';
import { activityVisibility } from './renderer/application-activity.ts';
import type { DesktopState } from './shared.ts';

const problem = (error: unknown) =>
  error instanceof Error
    ? error.message
    : 'Could not read Application activity.';

/**
 * Project Process inventory for the Application hub Activity view.
 * Server admins use fleet visibility so service-owned LLM logs appear;
 * other accounts stay on mine (known ownership gap).
 */
export class ApplicationActivityClient {
  private epoch = 0;
  private flights = new Map<string, Promise<void>>();
  private state: () => DesktopState;
  private send: (ask: Ask) => Promise<CheckedAnswer>;
  private emit: () => void;
  constructor(
    state: () => DesktopState,
    send: (ask: Ask) => Promise<CheckedAnswer>,
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  reset() {
    this.epoch++;
    this.flights.clear();
    delete this.state().applicationActivity;
  }
  private available(project: string) {
    if (
      !this.state().connected ||
      !this.state().projects.some(
        (row) => row.name === project && row.kind === 'application',
      )
    )
      throw new Error('Choose an available server Application.');
  }
  private read(key: string, work: () => Promise<void>) {
    const prior = this.flights.get(key);
    const next = (prior ?? Promise.resolve())
      .catch(() => {})
      .then(work)
      .finally(() => {
        if (this.flights.get(key) === next) this.flights.delete(key);
      });
    this.flights.set(key, next);
    return next;
  }
  async list(project: string) {
    this.available(project);
    return this.read(`list:${project}`, async () => {
      const epoch = ++this.epoch;
      const visibility = activityVisibility(this.state().serverAdmin);
      const prior = this.state().applicationActivity;
      this.state().applicationActivity = {
        project,
        items: prior?.items ?? [],
        loaded: prior?.loaded ?? false,
        loading: true,
        visibility,
        ...(prior?.selected ? { selected: prior.selected } : {}),
      };
      this.emit();
      try {
        const answer = await this.send({
          type: 'proc.list',
          payload: { project, visibility, limit: 100 },
        });
        if (answer.code !== 'OK')
          throw new Error(answer.said ?? 'PROC audit unavailable');
        const listed = decodeReply('proc.list', answer.payload);
        if (epoch !== this.epoch) return;
        this.available(project);
        const items = listed.conversations as ProcConversation[];
        const selected =
          prior?.selected && items.some((row) => row.id === prior.selected)
            ? prior.selected
            : undefined;
        this.state().applicationActivity = {
          project,
          items,
          loaded: true,
          loading: false,
          visibility,
          ...(selected ? { selected } : {}),
          ...(listed.cursor ? { cursor: listed.cursor } : {}),
        };
      } catch (error) {
        if (epoch === this.epoch)
          this.state().applicationActivity = {
            project,
            items: prior?.items ?? [],
            loaded: prior?.loaded ?? false,
            loading: false,
            visibility,
            error: problem(error),
            ...(prior?.selected ? { selected: prior.selected } : {}),
          };
      } finally {
        if (epoch === this.epoch) this.emit();
      }
    });
  }
  select(id: string) {
    const listed = this.state().applicationActivity;
    if (!listed?.items.some((row) => row.id === id))
      throw new Error('Choose an available activity row.');
    listed.selected = id;
    this.emit();
  }
}
