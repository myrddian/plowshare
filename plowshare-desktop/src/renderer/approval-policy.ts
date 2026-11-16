import { errorMessage } from 'plowshare-client-ts/binding/values';
import {
  isApprovalPolicy,
  type ApprovalPolicyView,
} from 'plowshare-client-ts/operations/approval-policy';
import { replyState, type DesktopApi, type DesktopState } from '../shared.ts';
import { background } from './events.ts';

/** Personal settings are owner-scoped server operations, never project-file writes. */
export function installApprovalPolicy(
  api: DesktopApi,
  state: () => DesktopState,
) {
  const dialog = document.createElement('dialog');
  dialog.className = 'operator-dialog';
  dialog.setAttribute('aria-label', 'Personal approval policy');
  dialog.innerHTML = `<header><h2>Personal approval policy</h2><button data-close type="button">Close</button></header>
    <p>SECURITY compares each covered action with your request. It can clear the action, reject it back to the agent, or ask you. Your bot cannot override an objection.</p>
    <p data-status role="status"></p><p data-error role="alert" hidden></p>
    <form><label>Approval mode<select name="mode"><option value="MANUAL">Manual — existing consent</option><option value="SMART">Smart — rules and SECURITY</option><option value="AUTO">Auto — Smart, then my bot</option></select></label>
    <label>Personal bot<select name="bot"><option value="">Select a personal bot</option></select></label>
    <label>Personal rules (JSON)<textarea name="rules" rows="12" maxlength="1000000" spellcheck="false" aria-describedby="approval-rule-help"></textarea></label>
    <p id="approval-rule-help">Rule effects: REJECT, ASK_HUMAN, MAY_APPROVE. Reject wins over ask, then allow. Automatic command rules require the exact project, provider, working directory and argument array. Empty rules: []. Guidance is context for review, never a permission grant.</p>
    <details><summary>Example command rule</summary><pre data-example></pre></details>
    <p data-coverage></p><div class="dialog-actions"><button data-reload type="button">Reload saved policy</button><button type="submit">Save policy</button></div></form>`;
  document.body.append(dialog);
  const form = dialog.querySelector('form')!;
  const mode = dialog.querySelector<HTMLSelectElement>('[name="mode"]')!;
  const bot = dialog.querySelector<HTMLSelectElement>('[name="bot"]')!;
  const rules = dialog.querySelector<HTMLTextAreaElement>('[name="rules"]')!;
  const status = dialog.querySelector<HTMLElement>('[data-status]')!;
  const error = dialog.querySelector<HTMLElement>('[data-error]')!;
  const coverage = dialog.querySelector<HTMLElement>('[data-coverage]')!;
  dialog.querySelector<HTMLElement>('[data-example]')!.textContent =
    JSON.stringify(
      [
        {
          id: 'inspect-status',
          effect: 'MAY_APPROVE',
          kind: 'COMMAND',
          project: '<project>',
          provider: '<provider identity>',
          cwd: '<absolute project directory>',
          inputDigest: null,
          argv: ['git', 'status'],
          paths: [],
          guidance: null,
        },
      ],
      null,
      2,
    );
  let saved: ApprovalPolicyView | undefined;
  let busy = false;
  let generation = 0;
  let openedIdentity = '';
  let openedAccount = '';
  const pendingDrafts = new Map<
    string,
    { mode: string; bot: string; rules: string }
  >();
  const account = () =>
    JSON.stringify([state().base, state().handle, state().selectedConnection]);
  const identity = () => {
    const now = state();
    return JSON.stringify([
      now.base,
      now.handle,
      now.selectedConnection,
      now.connected,
      now.informationConnection,
    ]);
  };
  const fail = (failure: unknown) => {
    error.textContent = errorMessage(failure);
    error.hidden = false;
  };
  function controls() {
    for (const control of form.querySelectorAll<
      | HTMLInputElement
      | HTMLSelectElement
      | HTMLButtonElement
      | HTMLTextAreaElement
    >('input, select, button, textarea'))
      control.disabled = busy || !saved || identity() !== openedIdentity;
    // Reload is also the explicit reconciliation path after an uncertain save.
    dialog.querySelector<HTMLButtonElement>('[data-reload]')!.disabled =
      busy || !state().connected;
    rules.disabled = false;
    rules.readOnly = busy || !saved || identity() !== openedIdentity;
    bot.disabled ||= mode.value !== 'AUTO';
  }
  function bots(selected: string | null, now = state()) {
    const names = now.personal
      ? (now.agents[now.personal.project] ?? [])
          .filter((agent) => agent.bot)
          .map((agent) => agent.name)
      : [];
    bot.replaceChildren(new Option('Select a personal bot', ''));
    for (const name of new Set([...names, ...(selected ? [selected] : [])]))
      bot.add(new Option(name, name));
    bot.value = selected ?? '';
  }
  function display(value: ApprovalPolicyView, botState = state()) {
    saved = value;
    mode.value = value.policy.mode;
    bots(value.policy.bot, botState);
    rules.value = JSON.stringify(value.policy.rules, null, 2);
    const capabilities = value.capabilities;
    coverage.textContent = `Direct agent commands and file tools only; native acceptance/stage checks keep their existing approvals. Coverage: ${capabilities.operations.join(', ')}. ${capabilities.humanHistory ? 'Previous exact human approvals can establish Smart eligibility.' : 'History-based eligibility is unavailable in this server build; use exact rules or existing consent.'} ${capabilities.additionalFileAccess ? 'Supported additional file access requires your explicit decision.' : 'Additional access outside provider fences is unavailable.'}`;
    status.textContent = `Saved revision ${value.policy.revision}.`;
  }
  async function load() {
    if (!state().connected)
      throw new Error('Connect to load your personal policy.');
    const token = ++generation;
    openedIdentity = identity();
    openedAccount = account();
    busy = true;
    error.hidden = true;
    status.textContent = 'Loading personal policy…';
    controls();
    try {
      const now = state();
      const reply = await api.request({
        action: 'approval-policy',
        operation: 'get',
      });
      if (token !== generation || identity() !== openedIdentity) return;
      if (!reply.approvalPolicy)
        throw new Error('The server returned no policy.');
      display(reply.approvalPolicy, now);
      const preserved = pendingDrafts.get(openedAccount);
      if (preserved) {
        mode.value = preserved.mode;
        bots(preserved.bot || null, now);
        rules.value = preserved.rules;
        pendingDrafts.delete(openedAccount);
        status.textContent = `Saved revision ${reply.approvalPolicy.policy.revision} loaded. Your draft is preserved; review it before saving. No change was resent.`;
      }
      // Bot choices are optional enrichment. Their lookup must not hold mode/rule controls
      // behind a second read, and a late result must not replace another account's draft.
      if (now.personal && now.agents[now.personal.project] === undefined) {
        background(
          api
            .request({ action: 'personal-bots' })
            .then((botReply) => {
              if (token !== generation || identity() !== openedIdentity) return;
              bots(bot.value || null, replyState(botReply));
              controls();
            })
            .catch((failure: unknown) => {
              if (token === generation && identity() === openedIdentity)
                fail(failure);
            }),
        );
      }
    } catch (failure) {
      if (token === generation && identity() === openedIdentity) fail(failure);
    } finally {
      if (token === generation) {
        busy = false;
        controls();
      }
    }
  }
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (busy || !saved || openedIdentity !== identity()) return;
    const current = saved;
    const token = ++generation;
    background(
      (async () => {
        error.hidden = true;
        try {
          const parsed: unknown = JSON.parse(rules.value);
          const candidate = {
            revision: current.policy.revision,
            mode: mode.value,
            bot: mode.value === 'AUTO' ? bot.value || null : null,
            rules: parsed,
          };
          if (!isApprovalPolicy(candidate))
            throw new Error(
              'Choose a valid mode and bot, and provide complete valid rules.',
            );
          if (!pendingDrafts.has(openedAccount) && pendingDrafts.size >= 8)
            throw new Error(
              'Reconcile the saved drafts for your other connections before submitting another policy.',
            );
          pendingDrafts.set(openedAccount, {
            mode: mode.value,
            bot: bot.value,
            rules: rules.value,
          });
          busy = true;
          controls();
          status.textContent = 'Saving policy…';
          const reply = await api.request({
            action: 'approval-policy',
            operation: 'save',
            policy: candidate,
          });
          if (token !== generation || identity() !== openedIdentity) return;
          if (!reply.approvalPolicy)
            throw new Error(
              'Save completion is unknown. Reload the saved policy before trying again.',
            );
          pendingDrafts.delete(openedAccount);
          display(reply.approvalPolicy);
        } catch (failure) {
          if (token === generation && identity() === openedIdentity) {
            fail(failure);
            status.textContent =
              'Draft preserved. Reload the saved policy to reconcile a failed or uncertain save; no change is automatically retried.';
            // Require explicit reload after any submitted mutation; its response may be lost.
            if (busy) saved = undefined;
          }
        } finally {
          if (token === generation) {
            busy = false;
            controls();
          }
        }
      })().catch(fail),
    );
  });
  mode.addEventListener('change', controls);
  dialog
    .querySelector('[data-close]')!
    .addEventListener('click', () => dialog.close());
  dialog
    .querySelector('[data-reload]')!
    .addEventListener('click', () => background(load().catch(fail)));
  return {
    open() {
      if (!dialog.open) dialog.showModal();
      saved = undefined;
      background(load().catch(fail));
    },
    update() {
      if (!dialog.open) return;
      if (identity() !== openedIdentity) {
        ++generation;
        busy = false;
        saved = undefined;
        if (account() !== openedAccount) {
          mode.value = 'MANUAL';
          bots(null);
          rules.value = '[]';
          coverage.textContent = '';
        }
        status.textContent =
          'Connection or account changed. Reload this account’s saved policy.';
      }
      controls();
    },
  };
}
