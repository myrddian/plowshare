import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import { CLI_OPERATIONS } from './catalog.ts'
import { BOUND_OPERATIONS } from './administration.ts'
import { MEMORY_OPERATIONS } from './direct.ts'
import { OBSERVER_OPERATIONS } from './observation.ts'
import { SYNC_OPERATIONS } from './union.ts'

const path = fileURLToPath(new URL('./direct.ts', import.meta.url))
const program = ts.createProgram([path], { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.NodeNext, moduleResolution: ts.ModuleResolutionKind.NodeNext, skipLibCheck: true })
const checker = program.getTypeChecker(), source = program.getSourceFile(path)!
const declaration = source.statements.find(node => ts.isInterfaceDeclaration(node) && node.name.text === 'Payloads')!
const payloads = checker.getTypeAtLocation(declaration)
function keys(operation: string): string[] {
    const property = payloads.getProperty(operation)
    expect(property, operation).toBeDefined()
    return checker.getTypeOfSymbolAtLocation(property!, declaration).getProperties().map(p => p.name).sort()
}
function record(file: string, name: string): string[] {
    const java = readFileSync(new URL('../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/' + file + '.java', import.meta.url), 'utf8')
    const matched = new RegExp(`record ${name}\\(([^)]*)\\)`, 's').exec(java)
    expect(matched, file + '.' + name).not.toBeNull()
    // Java generic commas are not record-field separators.
    return matched![1]!.replace(/<[^>]*>/g, '').split(',').map(field => field.trim().split(/\s+/).at(-1)!).sort()
}

describe('typed requests cover the actual server contracts', () => {
    it('covers every registered frame, including accounting snapshots', () => {
        const manifest = JSON.parse(readFileSync(new URL('../../../test-support/contracts/client-capabilities.json', import.meta.url), 'utf8'))
        const registered: string[] = manifest.websocketInventory.map((row: { frame: string }) => row.frame)
        const typed = payloads.getProperties().map(p => p.name).sort()
        expect(typed).toEqual(registered.sort())
        expect(manifest.websocketInventory.every((row: {typedPayload:boolean})=>row.typedPayload)).toBe(true)
        const cli = new Set([...Object.values(CLI_OPERATIONS), ...Object.values(MEMORY_OPERATIONS), ...Object.values(OBSERVER_OPERATIONS), ...Object.values(SYNC_OPERATIONS), 'conversation.search', 'job.status', 'job.cancel'])
        const remaining = Object.keys(BOUND_OPERATIONS).filter(frame => !cli.has(frame))
        expect([...cli, ...remaining].sort()).toEqual(typed)
        expect(cli.size).toBe(184)
        expect(remaining.sort()).toEqual(['incoming.cancel','incoming.catalog','incoming.receive','incoming.status','outgoing.advertise','outgoing.claim','outgoing.report','usage.subscribe','usage.unsubscribe'])
    })
    it.each([
        ['approval.list', 'ws/ApprovalFrames', 'ListBody', []], ['approval.answer', 'ws/ApprovalFrames', 'AnswerBody', []],
        ['approval.revoke', 'ws/ApprovalFrames', 'RevokeBody', []], ['inbox.list', 'ws/InboxListHandler', 'Body', []],
        ['inbox.read', 'ws/InboxReadHandler', 'Body', []], ['schedule.define', 'ws/ScheduleDefineHandler', 'Body', []],
        ['schedule.read', 'ws/ScheduleReadHandler', 'Body', []], ['schedule.pause', 'ws/SchedulePauseHandler', 'Body', []],
        ['trigger.define', 'ws/TriggerDefineHandler', 'Body', []], ['trigger.pause', 'ws/TriggerPauseHandler', 'Body', []],
        ['event.fire', 'ws/EventFireHandler', 'Body', []], ['firing.list', 'ws/FiringListHandler', 'Body', []],
        ['orchestration.list', 'ws/OrchestrationFrames', 'ListBody', []], ['orchestration.status', 'ws/OrchestrationFrames', 'IdBody', []],
        ['orchestration.answer', 'ws/OrchestrationFrames', 'AnswerBody', []], ['orchestration.cancel', 'ws/OrchestrationFrames', 'IdBody', []],
        ['orchestration.caps', 'ws/CapsFrames', 'CapsBody', []], ['orchestration.record', 'ws/RecordFrames', 'RecordWindow', []],
        ['message.instances', 'ws/MessageFrames', 'ListBody', []], ['message.instance.open', 'ws/MessageFrames', 'OpenBody', []],
        ['message.instance', 'ws/MessageFrames', 'IdBody', []], ['message.deliveries', 'ws/MessageFrames', 'DeliveriesBody', []],
        ['message.delivery', 'ws/MessageFrames', 'DeliveryBody', []],
        ['board.topics', 'ws/BoardInspectionFrames', 'ListBody', []],
        ['board.topup', 'api/BoardController', 'Topup', ['topic']], ['web.search', 'api/SearchController', 'SearchRequest', []],
    ] as const)('%s fields match the Java DTO and explicit path identifiers', (operation, file, name, extras) => {
        expect(keys(operation)).toEqual([...record(file, name), ...extras].sort())
    })
})
