// Test-only bridge: use maintained TypeScript adapters; never ship Java client facades.
import { requestIn } from '../../../../sdk/typescript/build/binding/files.js'
import { enforcing } from '../../../../sdk/node/build/enforcer.js'
import { respond } from '../../../../plowshare-mcp/build/protocol.js'
import { connectPlowshare } from '../../../../sdk/node/build/sdk.js'
let input = ''
for await (const chunk of process.stdin) input += chunk
const command = JSON.parse(input)
if (command.mode === 'files') {
    // Each test request names its exact root. Multiple-root fixtures are protocol
    // probes; actual Node presence lends one root per project.
    const roots = command.roots
    const fileRequest = requestIn(JSON.stringify(command.request))
    if (!fileRequest) throw new Error('invalid file fixture request')
    if (!roots.length) throw new Error('Node fixture requires an explicit root')
    const root = roots.find(root => command.request.path?.startsWith(root + '/') || command.request.path === root) ?? roots[0]
    const answer = enforcing(root, false)
    try { process.stdout.write(JSON.stringify(await answer(fileRequest))) }
    finally { answer.close() }
} else if (command.mode === 'mcp') {
    let active
    const backend = {
        invoke: async (type, payload) => {
            try {
                active ??= await connectPlowshare({ origin: command.origin, token: command.token, timeoutMs: 10000 })
                const reply = await active.request(type, payload)
                if (!['OK','CREATED','ACCEPTED','NO_CONTENT'].includes(reply.outcome.code)) throw new Error(reply.outcome.said ?? reply.outcome.code)
                return reply.outcome.payload
            } catch (failure) { throw new Error('could not reach the server or complete operation: ' + failure.message) }
        },
        root: async () => { throw new Error('fixture does not root files') },
        runSession: () => undefined,
    }
    try {
        const response = await respond(command.request, backend)
        if (response !== undefined) process.stdout.write(JSON.stringify(response))
    } finally { active?.close() }
}
