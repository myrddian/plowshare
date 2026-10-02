import { authenticateConfigured } from '../../plowshare-client-node/build/session.js';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
// Opt-in: synthetic scoped memories and bounded model calls. Curation uses normal server policy.
if (!process.argv.includes('--live'))
    throw Error('Pass --live to authorize the bounded disposable-project acceptance.');
const base = process.env.PLOWSHARE_URL || 'http://127.0.0.1:8091';
const models = (process.env.PLOWSHARE_ACCEPTANCE_MODELS || '').split(',').map(s => s.trim()).filter(Boolean);
if (!models.length)
    throw Error('Set PLOWSHARE_ACCEPTANCE_MODELS to the configured model selectors to validate.');
const output = process.env.PLOWSHARE_ACCEPTANCE_OUTPUT || new URL('../build/live-memory-models.json', import.meta.url);
const env = { ...process.env };
for (const k of ['PLOWSHARE_HANDLE', 'PLOWSHARE_PASSWORD', 'PLOWSHARE_PROJECT'])
    delete env[k];
const evidence = [], project = `maintenance_acceptance_${Date.now()}`, root = await mkdtemp(join(tmpdir(), 'plowshare-maintenance-')), abort = new AbortController();
let connection;
const active = new Set();
const record = row => { evidence.push(row); console.log(JSON.stringify(row)); };
const ask = async (type, payload) => { const r = await connection.ask(type, payload); if (!['OK', 'CREATED', 'ACCEPTED'].includes(r.code))
    throw Error(`${type}: ${r.code} ${r.said}`); return r.payload; };
async function wait(id, ms = 180000) { active.add(id); const until = Date.now() + ms; while (Date.now() < until) {
    const r = await ask('job.status', { job: id });
    if (r.id !== id)
        throw Error('Job identity mismatch');
    if (r.outcome) {
        active.delete(id);
        return r;
    }
    await new Promise(r => setTimeout(r, 1500));
} throw Error('Job deadline: ' + id); }
try {
    connection = await authenticateConfigured(base, env, abort.signal, () => { });
    await connection.root(project, root);
    await writeFile(join(root, 'probe.txt'), 'PLOWSHARE_LIVE_EVIDENCE_34: the orchid fixture has seven blue petals.\n');
    for (const [index, model] of models.entries()) {
        try {
            const agent = `acceptance_model_${index}`;
            const d = await ask('agent.define', { project, name: agent, text: `---\nname: ${agent}\ndescription: Bounded live tool acceptance.\nmodel: ${model}\nexported: true\nbot: true\nscopes: [workspace:read]\ntools: [file_read]\nmax-turns: 3\nmax-model-calls: 3\n---\nRead the requested file using file_read, then return the marker, the stated number and a citation to probe.txt. Never invent its contents.\n` });
            if (!d.agent.served)
                throw Error('Configured model not served: ' + JSON.stringify(d.agent.withheld));
            const conversation = await ask('conversation.open', { project, maxTurns: 3, maxModelCalls: 3 });
            const job = await ask('agent.run', { agent, task: `Read ${join(root, 'probe.txt')} with file_read. Return the exact marker and number of blue petals, citing probe.txt.`, conversation: conversation.id, session: connection.runSession() });
            const result = await wait(job.id);
            const trace = await ask('conversation.trajectory', { conversation: conversation.id, limit: 100 });
            const calls = trace.entries.flatMap(e => e.toolCalls ?? []);
            if (!result.outcome.answered || !result.outcome.text.includes('PLOWSHARE_LIVE_EVIDENCE_34') || !calls.some(c => c.name === 'file_read'))
                throw Error('Model did not complete the tool-backed evidence task: ' + JSON.stringify(result.outcome));
            record({ operation: 'configured-model.tool-turn', model, agent, job: job.id, conversation: conversation.id, ending: result.outcome.ending, modelCalls: result.outcome.modelCalls, tools: calls.map(c => c.name), status: 'passed' });
        }
        catch (error) {
            record({ operation: 'configured-model.tool-turn', model, status: 'failed', error: String(error) });
        }
    }
    for (const stage of ['write', 'digest', 'curate', 'navigate']) {
        try {
            if (stage === 'write') {
                const r = await ask('memory.write', { project, proposal: { summary: 'Orchid fixture in this disposable acceptance project', scope: `Only the disposable project ${project}; never generalize to other projects`, body: 'This synthetic acceptance fixture has seven blue petals. It is not a fact about any real orchid. Retain only in this project.', formedBy: connection.handle, formedWhere: project } });
                record({ operation: 'memory.write', status: 'passed', receipt: r });
                const second = await ask('memory.write', { project, proposal: { summary: 'Disposable bronze widget fixture', scope: `Only widget B in ${project}; unrelated to the orchid fixture`, body: 'The synthetic widget fixture uses three bronze rivets. This applies only to this disposable project and is not a general engineering rule.', formedBy: connection.handle, formedWhere: project } });
                record({ operation: 'memory.write.second-root', status: 'passed', receipt: second });
                const rows = await ask('memory.index', { project });
                if (rows.length !== 2)
                    throw Error('Expected two distinct scoped synthetic memories');
                const read = await ask('memory.read', { memory: r.memoryId });
                if (!read.body.includes('seven blue petals'))
                    throw Error('Read lost the accepted body');
                record({ operation: 'memory.read', status: 'passed', memory: r.memoryId, unsearchable: rows.find(row => row.id === r.memoryId)?.unsearchable });
            }
            else if (stage === 'navigate') {
                const r = await ask('memory.navigate', { project, question: 'How many blue petals does this project acceptance fixture have?' });
                if (!r.complete || !r.text.includes('seven'))
                    throw Error('Incomplete navigation: ' + JSON.stringify(r));
                record({ operation: 'memory.navigate', status: 'passed', level: r.level, ids: r.ids });
            }
            else {
                const type = stage === 'curate' ? 'agent.curate' : 'memory.digest';
                const job = await ask(type, { project, ...(stage === 'curate' ? { maxModelCalls: 4 } : {}) });
                const r = await wait(job.id);
                if (!r.outcome.answered || stage === 'digest' && r.outcome.modelCalls < 1)
                    throw Error('Maintenance did not answer: ' + JSON.stringify(r.outcome));
                record({ operation: type, status: 'passed', job: job.id, ending: r.outcome.ending, modelCalls: r.outcome.modelCalls, text: r.outcome.text });
                if (stage === 'curate') {
                    const rows = await ask('proposal.list', { project });
                    record({ operation: 'proposal.list', status: 'passed', count: rows.length, decision: 'pending proposals left unresolved; no explicit proposal.resolve sent' });
                }
            }
        }
        catch (error) {
            record({ operation: stage === 'curate' ? 'agent.curate' : `memory.${stage}`, status: 'failed', error: String(error) });
        }
    }
}
catch (error) {
    record({ operation: 'acceptance', status: 'failed', error: String(error) });
}
finally {
    if (connection) {
        for (const job of active)
            try {
                const r = await ask('job.cancel', { job });
                record({ operation: 'job.cancel', job, status: 'confirmed' });
            }
            catch (error) {
                record({ operation: 'job.cancel', job, status: 'uncertain', error: String(error) });
            }
        await connection.release();
        connection.close();
    }
    await writeFile(output, JSON.stringify({ project, evidence }, null, 2) + '\n', { mode: 0o600 });
    await rm(root, { recursive: true, force: true });
}
if (evidence.some(row => row.status === 'failed'))
    process.exitCode = 1;
