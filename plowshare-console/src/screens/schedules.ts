import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import type { ScheduleRecord } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import type { ScheduleFile } from '../../../sdk/typescript/src/operations/schedule-files.ts';
import { background } from '../background.ts';
import type { EventStream, EventStreamOptions } from '../events';
import { button, el, field } from './dom';
import { ScheduleControlRefused, SdkScheduleControls } from './schedule-port';
import type { Screen } from './screen';

/** A shared-socket runtime view. Read retained state after controls; never replay mutations. */
export function createSchedules(options: {
  root: HTMLElement;
  session: string;
  openStream: (options: EventStreamOptions) => EventStream;
}): Screen {
  const root = options.root;
  const reload = button('reload', 'Refresh schedules');
  const error = el('p', 'error');
  error.setAttribute('role', 'alert');
  const rows = el('div', 'schedule-list');
  root.append(
    el('h2', 'screen-title', 'Schedules'),
    el(
      'p',
      '',
      'Pause stops future occurrences. For Application schedules, it also clears waiting firings; Resume schedules the next future occurrence. Existing runs continue.',
    ),
    reload,
    error,
    rows,
  );
  const stream: EventStream = options.openStream({
    session: options.session,
    onEvent: () => {},
  });
  const port = new SdkScheduleControls(checkedTransport(stream));
  let closed = false,
    epoch = 0,
    busy = false;
  let schedules: readonly ScheduleRecord[] = [];
  let files: readonly ScheduleFile[] = [];
  // Unknown deliveries remain fenced until a fresh authoritative listing establishes
  // the requested runtime state. Refresh only reads; it never repeats a control.
  const unsettled = new Map<string, boolean>();

  function draw() {
    rows.replaceChildren();
    for (const schedule of schedules) {
      const file = files.find((item) => item.internalName === schedule.name);
      const row = el('section', 'schedule-row');
      row.dataset['schedule'] = schedule.name;
      row.append(
        el('h3', '', file?.name ?? schedule.name),
        field('Project', file?.project ?? 'Global / legacy'),
        field('Schedule', schedule.name),
        field('State', schedule.paused ? 'Paused' : 'Active'),
        field('Timing', `${schedule.cron} (${schedule.zone})`),
        field('Next occurrence', schedule.nextFireAt),
        field('Owner', schedule.definedBy),
      );
      if (file?.status === 'refused')
        row.append(el('p', 'error', file.error ?? 'Schedule source refused.'));
      if (unsettled.has(schedule.name))
        row.append(
          el(
            'p',
            '',
            'Control outcome pending. Refresh to inspect current state.',
          ),
        );
      const control = button(
        'schedule-control',
        schedule.paused ? 'Resume' : 'Pause',
      );
      control.setAttribute(
        'aria-label',
        `${schedule.paused ? 'Resume' : 'Pause'} ${file?.name ?? schedule.name}`,
      );
      control.disabled =
        busy || unsettled.has(schedule.name) || file?.status === 'refused';
      control.addEventListener('click', () => background(change(schedule)));
      row.append(control);
      rows.append(row);
    }
    if (schedules.length === 0)
      rows.append(el('p', '', 'No schedules are available to this account.'));
    reload.disabled = busy;
  }

  async function load() {
    if (closed || busy) return;
    const stamp = ++epoch;
    try {
      const [listed, sources] = await Promise.all([port.list(), port.files()]);
      if (closed || stamp !== epoch) return;
      schedules = listed;
      files = sources;
      for (const schedule of listed) {
        if (unsettled.get(schedule.name) === schedule.paused)
          unsettled.delete(schedule.name);
      }
      error.textContent = unsettled.size
        ? 'Some control outcomes remain pending; no request was repeated.'
        : '';
      draw();
    } catch (failure) {
      if (closed || stamp !== epoch) return;
      error.textContent =
        failure instanceof Error
          ? failure.message
          : 'Schedule inspection failed.';
      draw(); // Keep the last confirmed state and any uncertainty fence.
    }
  }

  async function change(schedule: ScheduleRecord) {
    if (closed || busy || unsettled.has(schedule.name)) return;
    busy = true;
    unsettled.set(schedule.name, !schedule.paused);
    draw();
    try {
      await port.pause(schedule.name, !schedule.paused);
    } catch (failure) {
      if (closed) return;
      if (failure instanceof ScheduleControlRefused)
        unsettled.delete(schedule.name);
      error.textContent =
        failure instanceof Error
          ? failure.message
          : 'Control completion is unknown. Refresh to inspect state.';
      busy = false;
      draw();
      return;
    }
    busy = false;
    await load();
  }

  reload.addEventListener('click', () => background(load()));
  return {
    element: () => root,
    load,
    destroy() {
      closed = true;
      epoch++;
      stream.close();
      root.replaceChildren();
    },
  };
}
