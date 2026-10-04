import { connect, ConnectionFault } from '../binding/connection.ts'
import type { Connected, ConnectOptions, Reply } from '../binding/connection.ts'
import { succeeded } from '../binding/codes.ts'
import { OPERATION_SCHEMAS } from './operation-schemas.ts'
import type { Payloads } from './direct.ts'

export { ConnectionFault } from '../binding/connection.ts'
export type { Reply, ConnectOptions } from '../binding/connection.ts'
export type { Payloads } from './direct.ts'
export type { Replies } from './replies.ts'
export type { Outcome } from '../binding/envelope.ts'
export type { JobView, JobOutcome } from '../binding/job-view.ts'
export type { OutgoingWork } from './outgoing.ts'
export { JobLifecycle } from '../jobs/lifecycle.ts'
export const OPERATIONS: readonly (keyof Payloads)[] = Object.freeze(Object.keys(OPERATION_SCHEMAS.inputs).sort() as (keyof Payloads)[])

export class Refusal extends Error {
    readonly reply: Reply
    constructor(reply: Reply) { super(reply.outcome.said ?? reply.outcome.code); this.reply = reply }
}
export function requirePayload(reply: Reply): unknown {
    if (!succeeded(reply.outcome.code)) throw new Refusal(reply)
    if (reply.outcome.payload === undefined) throw new ConnectionFault('INVALID_ENVELOPE', 'successful response omitted a required payload; outcome is unresolved')
    return reply.outcome.payload
}
/** SDK over the shared transport. All registered operations retain their complete wire reply. */
export class Plowshare {
    private readonly connection: Connected
    readonly session: string | undefined
    constructor(options: ConnectOptions & { readonly session?: string }) { this.connection = connect(options); this.session = options.session }
    request<K extends keyof Payloads>(type: K, payload: Payloads[K]): Promise<Reply> {
        if (!OPERATIONS.includes(type)) throw new Error('unknown Plowshare operation')
        return this.connection.request(type, payload)
    }
    jobStatus(job: string): Promise<Reply> { return this.request('job.status', { job }) }
    cancelJob(job: string): Promise<Reply> { return this.request('job.cancel', { job }) }
    openConversation(payload: Payloads['conversation.open'] = {}): Promise<Reply> { return this.request('conversation.open', payload) }
    runAgent(payload: Payloads['agent.run']): Promise<Reply> { return this.request('agent.run', {
        ...(this.session === undefined ? {} : { session: this.session }), ...payload,
    }) }
    sendOutgoing(payload: Payloads['outgoing.send']): Promise<Reply> { return this.request('outgoing.send', payload) }
    outgoingStatus(id: string): Promise<Reply> { return this.request('outgoing.status', { id }) }
    cancelOutgoing(id: string): Promise<Reply> { return this.request('outgoing.cancel', { id }) }
    close(): void { this.connection.close() }
}
