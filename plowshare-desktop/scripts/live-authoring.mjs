import { DesktopWorkspace } from '../src/workspace.ts';
import { mkdtemp, realpath, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { authenticateConfigured } from '../../plowshare-client-node/build/session.js';
import { runQuestion } from '../src/shared.ts';
// Opt-in: this spends model calls and persists a disposable server project.
if (!process.argv.includes('--live'))
    throw Error('Pass --live to authorize the bounded disposable-project acceptance.');
const base = process.env.PLOWSHARE_URL || 'http://127.0.0.1:8091';
const output = process.env.PLOWSHARE_ACCEPTANCE_OUTPUT || new URL('../build/live-authoring.json', import.meta.url);
for (const key of ['PLOWSHARE_HANDLE', 'PLOWSHARE_PASSWORD', 'PLOWSHARE_PROJECT'])
    delete process.env[key];
const root = await realpath(await mkdtemp(join(tmpdir(), 'plowshare-builder-live-'))), project = `authoring_acceptance_${Date.now()}`, saved = [], evidence = [];
const workspace = new DesktopWorkspace(() => { }, { async list() { return saved; }, async put(row) { saved.push(row); }, async remove() { } });
const intent = 'Acceptance test: author a minimal read-only source_note procedure. One stage reads brief.txt using file_read, then returns a concise cited source note as its final result. No command execution, file writes by the resulting procedure, delegated agents, nested orchestrations, external services or installation until explicit human review. A missing file is a refusal. Allow at most 5 model calls and 5 turns. Stage done-when is model guidance, with no fake executable checks. Use this complete brief to draft and validate through Studio, then ask the person whether to install. This is a disposable test project. Do not run the resulting procedure.';
let session, installed = false, runId, executionRunId, conversation, answered = 0, last = '';
const record = row => { evidence.push(row); console.log(JSON.stringify(row)); };
try {
    await writeFile(join(root, 'brief.txt'), 'A source note describes only the supplied evidence. Missing evidence must be stated.\n');
    await workspace.dispatch({ action: 'connect', base, handle: '', password: '' });
    await workspace.rootDirectory(root, project);
    session = await authenticateConfigured(base, process.env, new AbortController().signal, () => { });
    const defined = await session.ask('agent.define', { project, name: 'acceptance_author', text: '---\nname: acceptance_author\ndescription: Starts the required builder in this disposable project.\nmodel: reasoning\nexported: true\nbot: true\nscopes: [workspace:write]\ntools: [file_roots, file_read, file_glob, file_edit]\norchestrations: [design_orchestration]\nmax-turns: 8\nmax-model-calls: 12\n---\nImmediately invoke the granted design_orchestration with the supplied intent. Do not author or install yourself.\n' });
    if (defined.code !== 'CREATED' || !defined.payload.agent.served)
        throw Error('Acceptance author is not served: ' + defined.said);
    await workspace.dispatch({ action: 'builder-prepare', project });
    const role = workspace.state.agents[project]?.find(row => row.name === 'acceptance_author' && row.served && row.orchestrations.includes('design_orchestration'));
    if (!role)
        throw Error('No granted caller served in disposable project');
    const opened = await workspace.dispatch({ action: 'builder-start', project, agent: role.name, intent });
    conversation = opened.conversation;
    record({ operation: 'desktop.builder.start', project, conversation, caller: role.name, bot: role.bot, job: opened.state.jobs.at(-1)?.id, status: opened.state.authoring.status });
    const until = Date.now() + 420000;
    while (Date.now() < until) {
        await workspace.dispatch({ action: 'activity-view', view: 'builder' });
        const run = workspace.state.activity.runs.items.find(row => row.definition === 'design_orchestration' && row.callerConversation === conversation);
        if (run) {
            runId = run.id;
            await workspace.dispatch({ action: 'run-detail', id: run.id });
            await workspace.dispatch({ action: 'run-record', id: run.id });
            const state = workspace.state, wire = state.activity.details[run.id]?.wire;
            const key = JSON.stringify([run.id, wire?.orchestration.state, wire?.messages.length]);
            if (key !== last) {
                last = key;
                record({ operation: 'builder.status', run: run.id, state: wire?.orchestration.state, pendingCap: wire?.orchestration.pendingCap, recordRows: state.activity.records?.[run.id]?.rows.length });
            }
            if (wire?.orchestration.state === 'asking') {
                const question = [...wire.messages].reverse().find(row => row.kind === 'question'), structure = question?.structure;
                if (wire.orchestration.pendingCap === 'install') {
                    record({ operation: 'builder.review', run: run.id, name: structure?.name, path: structure?.path, sha256: structure?.sha256, sourceCharacters: structure?.text?.length, question: question?.id });
                    const source = structure?.text;
                    if (typeof source !== 'string' || 'sha256:' + createHash('sha256').update(source).digest('hex') !== structure.sha256 || structure.name !== 'source_note')
                        throw Error('Installation review identity/hash mismatch');
                    const front = source.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)?.[1];
                    if (!front || !/^tools:\s*\[(?:file_roots,\s*)?file_read(?:,\s*file_roots)?\]\s*$/m.test(front) || !/^scopes:\s*\[workspace:read\]\s*$/m.test(front) || !/^max-turns:\s*[1-5]\s*$/m.test(front) || !/^max-model-calls:\s*[1-5]\s*$/m.test(front) || /^(calls|orchestrations|hooks|commands|checks|extends):/m.test(front))
                        throw Error('Draft exceeded the agreed read-only grants/budget');
                    record({ operation: 'builder.review.acceptance', sha256: structure.sha256, grants: ['file_read', 'optional file_roots', 'workspace:read'], bounded: true });
                    const choices = structure.questions.map(q => ({ header: q.header, chosen: [q.options.find(o => o.label === 'Install')?.label].filter(Boolean) }));
                    if (choices.some(q => !q.chosen.length))
                        throw Error('No explicit installation offered');
                    await workspace.dispatch({ action: 'run-answer', id: run.id, question: runQuestion(wire), choices });
                    installed = true;
                    record({ operation: 'builder.account-install', run: run.id, status: 'confirmed' });
                    break;
                }
                if (++answered > 4)
                    throw Error('Interview exceeded the bounded acceptance answers');
                const answer = intent + ` The verified workspace is ${root}. If you need a draft path, use ${root}/docs/orchestrations/2026-10-02-design_orchestration-${run.id}/source_note.md, within the run artifacts directory. ` + ' Please continue with this agreed acceptance scope. Validation lints must be reported; never widen the grants.';
                await workspace.dispatch({ action: 'run-answer', id: run.id, question: runQuestion(wire), ...(structure?.questions ? { choices: structure.questions.map(q => ({ header: q.header, chosen: [], ...(q.options.some(o => o.label === 'accept') ? { chosen: ['accept'], note: 'Acceptance scope agreed; installation still needs the exact-byte review.' } : { chosen: [], other: answer }) })) } : { answer }) });
                record({ operation: 'builder.interview.answer', run: run.id, question: question?.id, status: 'confirmed' });
            }
            else if (wire && !['running', 'waiting'].includes(wire.orchestration.state)) {
                record({ operation: 'builder.terminal', run: run.id, state: wire.orchestration.state, failure: wire.orchestration.failure });
                break;
            }
        }
        await new Promise(resolve => setTimeout(resolve, 2000));
    }
    if (!installed)
        throw Error('The model did not reach validated installation review within the bounded acceptance');
    const checked = async (type, payload) => { const r = await session.ask(type, payload); if (!['OK', 'CREATED', 'ACCEPTED'].includes(r.code))
        throw Error(type + ' refused: ' + r.said); return r.payload; };
    const definitions = await checked('orchestration.definitions', { project });
    if (!definitions.definitions.some(d => d.name === 'source_note' && d.served && d.tier === 'project'))
        throw Error('Installed project definition is not served');
    record({ operation: 'builder.installed', name: 'source_note', project });
    await checked('agent.define', { project, name: 'acceptance_caller', text: '---\nname: acceptance_caller\ndescription: Bounded orchestration execution acceptance.\nexported: true\nbot: true\nmodel: reasoning\nscopes: [workspace:read]\ntools: [file_read]\norchestrations: [source_note]\nmax-turns: 5\nmax-model-calls: 5\n---\nCall source_note to read brief.txt and return its cited result. Do not answer without running the procedure. After starting it, report its handle; do not cancel it.\n' });
    await workspace.dispatch({ action: 'refresh' });
    const openedRun = await workspace.dispatch({ action: 'create', project });
    await workspace.dispatch({ action: 'run', conversation: openedRun.conversation, agent: 'acceptance_caller', text: 'Run source_note now. Its one stage reads brief.txt in the connected project workspace and returns a concise cited note. Invoke the granted orchestration rather than answering yourself.' });
    const deadline = Date.now() + 180000;
    let executed;
    while (Date.now() < deadline) {
        const rows = await checked('orchestration.list', { project, limit: 100 });
        executed = rows.orchestrations.find(r => r.definition === 'source_note' && r.callerConversation === openedRun.conversation);
        if (executed) executionRunId = executed.id;
        if (executed && !['running', 'waiting', 'asking'].includes(executed.state))
            break;
        await new Promise(resolve => setTimeout(resolve, 2000));
    }
    if (executed?.state !== 'finished')
        throw Error('Installed procedure did not finish: ' + executed?.state);
    const status = await checked('orchestration.status', { id: executed.id });
    const trajectory = await checked('conversation.trajectory', { conversation: status.orchestration.conductorConversation, limit: 100 });
    const calls = trajectory.entries.flatMap(e => e.toolCalls ?? []);
    if (!calls.some(c => c.name === 'file_read'))
        throw Error('Procedure completed without the required real file_read call');
    if (!status.orchestration.result?.includes('brief.txt'))
        throw Error('Procedure result does not cite the supplied brief');
    record({ operation: 'builder.installed.run', run: executed.id, state: executed.state, tools: calls.map(c => c.name), result: status.orchestration.result });
    const page = workspace.state.activity.records?.[runId];
    record({ operation: 'builder.records', run: runId, tools: page?.rows.filter(row => row.kind === 'tool_call').map(row => row.text), limited: page?.more });
}
catch (error) {
    record({ operation: 'builder.acceptance', status: 'incomplete', error: String(error) });
    for (const job of workspace.state.jobs.filter(j => j.status !== 'finished'))
        try {
            await workspace.dispatch({ action: 'cancel', job: job.id });
            record({ operation: 'caller.cancel', job: job.id, status: 'confirmed' });
        }
        catch (error) {
            record({ operation: 'caller.cancel', job: job.id, status: 'uncertain', error: String(error) });
        }
    for (const id of [runId, executionRunId].filter(Boolean))
        try {
            await workspace.dispatch({ action: 'run-cancel', id });
            record({ operation: 'orchestration.cancel', run: id, status: 'confirmed' });
        }
        catch (error) {
            record({ operation: 'orchestration.cancel', run: id, status: 'uncertain', error: String(error) });
        }
}
finally {
    if (session) {
        await session.release();
        session.close();
    }
    await writeFile(output, JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600 });
    await workspace.shutdown();
    await rm(root, { recursive: true, force: true });
}
if (!evidence.some(row => row.operation === 'builder.installed.run'))
    process.exitCode = 1;
