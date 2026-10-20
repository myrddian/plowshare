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
  const sourceRows = el('div', 'schedule-files');
  const scheduleNavigation = el('nav', 'schedule-pages'),
    fileNavigation = el('nav', 'schedule-file-pages');
  scheduleNavigation.setAttribute('aria-label', 'Schedules pages');
  fileNavigation.setAttribute('aria-label', 'Schedule files pages');
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
    scheduleNavigation,
    el('h2', '', 'Schedule files'),
    sourceRows,
    fileNavigation,
  );
  const stream: EventStream = options.openStream({
    session: options.session,
    onEvent: () => {},
  });
  const port = new SdkScheduleControls(checkedTransport(stream));
  let closed = false,
    epoch = 0,
    busy = false,
    refreshing = false;
  let schedules: readonly ScheduleRecord[] = [];
  let files: readonly ScheduleFile[] = [];
  const pages = {
    schedules: {
      cursors: [undefined] as (string | undefined)[],
      index: 0,
      more: false,
      loading: false,
      revision: 0,
      error: '',
    },
    files: {
      cursors: [undefined] as (string | undefined)[],
      index: 0,
      more: false,
      loading: false,
      revision: 0,
      error: '',
    },
  };
  function navigation(section: 'schedules' | 'files', container: HTMLElement) {
    const page = pages[section],
      label = section === 'schedules' ? 'schedules' : 'schedule files';
    container.replaceChildren();
    for (const [direction, text, blocked] of [
      ['previous', 'Previous', page.index === 0],
      ['next', 'More', !page.more],
      ['first', 'First page', page.index === 0],
    ] as const) {
      const control = button(`page-${direction}`, text);
      control.setAttribute('aria-label', `${text} ${label}`);
      control.disabled = blocked || busy || page.loading;
      control.addEventListener('click', () =>
        background(turnPage(section, direction)),
      );
      container.append(control);
    }
    const status = el(
      'span',
      '',
      `Page ${page.index + 1}${page.loading ? ' · Loading…' : ''}`,
    );
    status.setAttribute('role', 'status');
    container.append(status);
    if (page.error) {
      const failed = el(
        'p',
        'error',
        `${page.error} The displayed page is retained.`,
      );
      failed.setAttribute('role', 'alert');
      container.append(failed);
    }
  }
  function reconcile(listed: readonly ScheduleRecord[]) {
    const pending = unsettled.size > 0;
    for (const schedule of listed)
      if (unsettled.get(schedule.name) === schedule.paused)
        unsettled.delete(schedule.name);
    if (pending && !unsettled.size) error.textContent = '';
  }

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
        field(
          'Project',
          file ? (file.project ?? 'Global') : 'Source not on this file page',
        ),
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
        busy ||
        pages.schedules.loading ||
        unsettled.has(schedule.name) ||
        file?.status === 'refused';
      control.addEventListener('click', () => background(change(schedule)));
      row.append(control);
      rows.append(row);
    }
    if (schedules.length === 0)
      rows.append(el('p', '', 'No schedules are available on this page.'));
    sourceRows.replaceChildren();
    for (const file of files) {
      const row = el('section', 'schedule-file');
      row.append(
        el('h3', '', file.name),
        field('Project', file.project ?? 'Global'),
        field('Path', file.path),
        field('State', file.status),
      );
      if (file.error) row.append(el('p', 'error', file.error));
      sourceRows.append(row);
    }
    if (!files.length)
      sourceRows.append(el('p', '', 'No schedule files on this page.'));
    navigation('schedules', scheduleNavigation);
    navigation('files', fileNavigation);
    reload.disabled = busy || refreshing;
  }

  async function load(reset = true) {
    if (closed || busy) return;
    const stamp = ++epoch;
    refreshing = true;
    await Promise.all(
      (['schedules', 'files'] as const).map(async (section) => {
        const page = pages[section],
          revision = ++page.revision;
        page.loading = true;
        draw();
        const current = () =>
          !closed && stamp === epoch && revision === page.revision;
        try {
          const after = reset ? undefined : page.cursors[page.index];
          const loaded =
            section === 'schedules'
              ? { section: 'schedules' as const, rows: await port.list(after) }
              : { section: 'files' as const, rows: await port.files(after) };
          if (!current()) return;
          if (loaded.section === 'schedules') {
            schedules = loaded.rows.slice(0, 50);
            reconcile(schedules);
          } else files = loaded.rows.slice(0, 50);
          if (reset) {
            page.index = 0;
            page.cursors = [undefined];
          }
          page.error = '';
          page.more = loaded.rows.length > 50;
        } catch (failure) {
          if (current())
            page.error =
              failure instanceof Error
                ? failure.message
                : 'Schedule inspection failed.';
        } finally {
          if (current()) {
            page.loading = false;
            refreshing = Object.values(pages).some((value) => value.loading);
            draw();
          }
        }
      }),
    );
  }

  async function turnPage(
    section: 'schedules' | 'files',
    direction: 'next' | 'previous' | 'first',
  ) {
    const page = pages[section];
    if (closed || busy || page.loading) return;
    if (
      (direction === 'next' && !page.more) ||
      (direction === 'previous' && page.index === 0)
    )
      return;
    const index =
      direction === 'first' ? 0 : page.index + (direction === 'next' ? 1 : -1);
    const after =
      direction === 'next'
        ? section === 'schedules'
          ? schedules.at(-1)?.name
          : files.at(-1)?.internalName
        : page.cursors[index];
    if (direction === 'next' && after === undefined) return;
    const generation = epoch,
      revision = ++page.revision;
    page.loading = true;
    draw();
    try {
      const loaded =
        section === 'schedules'
          ? { section: 'schedules' as const, rows: await port.list(after) }
          : { section: 'files' as const, rows: await port.files(after) };
      if (closed || generation !== epoch || revision !== page.revision) return;
      if (loaded.section === 'schedules') {
        schedules = loaded.rows.slice(0, 50);
        reconcile(schedules);
      } else files = loaded.rows.slice(0, 50);
      page.index = index;
      page.cursors[index] = after;
      page.cursors.length = index + 1;
      page.more = loaded.rows.length > 50;
      page.error = '';
    } catch (failure) {
      if (!closed && generation === epoch && revision === page.revision)
        page.error =
          failure instanceof Error
            ? failure.message
            : 'Schedule page unavailable.';
    } finally {
      if (!closed && generation === epoch && revision === page.revision) {
        page.loading = false;
        draw();
      }
    }
  }

  async function change(schedule: ScheduleRecord) {
    if (
      closed ||
      busy ||
      pages.schedules.loading ||
      unsettled.has(schedule.name)
    )
      return;
    if (
      !schedules.some((row) => JSON.stringify(row) === JSON.stringify(schedule))
    )
      return;
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
    await load(false);
  }

  reload.addEventListener('click', () => background(load()));
  return {
    element: () => root,
    load: () => load(),
    destroy() {
      closed = true;
      epoch++;
      stream.close();
      root.replaceChildren();
    },
  };
}
