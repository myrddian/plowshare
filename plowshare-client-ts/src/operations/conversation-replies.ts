import type { Outcome } from '../binding/envelope.ts'
import { bool, count, finite, integer, list, named, noContent, nullable, positive, record, strings, text, type Check } from './wire-checks.ts'

/** Full wire records, kept separate from the smaller frontend display projections. */
export interface ConversationView {
    readonly id: string; readonly project: string | null; readonly maxModelCalls: number | null; readonly modelCallsSpent: number | null
    readonly maxTurns: number | null; readonly noTurnCap: boolean; readonly noBudget: boolean; readonly title: string | null
}
export interface ProjectView { readonly name: string; readonly workspace: string; readonly lent: readonly string[]; readonly exclusions: readonly string[]; readonly machine: string | null; readonly members: readonly string[]; readonly kind?: string; readonly type?: string; readonly readOnly?: boolean; readonly writePaths?: readonly string[]; readonly displayName?: string; readonly routingIdentity?: string; readonly role?: ProjectRole | null }
export type ProjectRole = 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER'
export interface ProjectGrant { readonly handle: string; readonly role: ProjectRole }
export interface ProjectGrantChange { readonly id: number; readonly occurredAt: string; readonly actor: string; readonly target: string; readonly action: string; readonly role: ProjectRole | null }
export interface ProjectAccess { readonly project: string; readonly role: ProjectRole; readonly permissions: readonly string[]; readonly members: readonly ProjectGrant[]; readonly history: readonly ProjectGrantChange[] }
export interface ProjectMembers { readonly project: string; readonly members: readonly string[] }
/** Server-resolved discovery metadata. Clients do not inspect package files. */
export type CommandEntry = {
    readonly command: string; readonly aliases: readonly string[]; readonly kind: 'skill' | 'orchestration'
    readonly name: string; readonly description: string; readonly argumentHint: string
    readonly executor: string; readonly mode: 'INHERITED' | 'SUMMARISED' | 'NEW' | 'DIRECT' | null
    readonly tier: string; readonly hash: string
    readonly agentVisible?: boolean
}
export interface AgentView {
    readonly name: string; readonly tools: readonly string[]; readonly calls: readonly string[]; readonly scopes: readonly string[]
    readonly served: boolean; readonly withheld: readonly string[]; readonly bot: boolean; readonly description: string
    readonly preferred: boolean; readonly model: string | null; readonly orchestrations: readonly string[]
    readonly skills?: readonly string[]; readonly commands?: readonly CommandEntry[]
    readonly displayName?: string | null; readonly origin?: string | null
}
export interface DefinedAgent { readonly agent: AgentView; readonly restartRequired: boolean }
export interface LifecycleView { readonly id: string; readonly lifecycle: string }
export interface TurnView { readonly ordinal: number; readonly utterance: string; readonly answer: string; readonly ending: string; readonly promptTokens: number | null }
export interface CompactionView { readonly throughOrdinal: number; readonly summary: string }
export interface OpenedView { readonly conversation: string; readonly agent: string }
export interface AskedView { readonly id: string; readonly name: string; readonly arguments: string; readonly length: number; readonly cut: boolean; readonly salient: string | null; readonly opened: OpenedView | null }
export interface EntryView {
    readonly ordinal: number; readonly turnOrdinal: number; readonly kind: string; readonly excerpt: string | null; readonly length: number
    readonly cut: boolean; readonly ejectedAt: string | null; readonly supersededBy: number | null; readonly toolCallId: string | null
    readonly toolCalls: readonly AskedView[]; readonly handle: string | null; readonly recordedAt: string | null; readonly tookMillis: number | null
    readonly dispatch: string | null; readonly wireModel: string | null; readonly completion: string | null
    readonly speaker: string | null; readonly speakerName: string | null; readonly outcome: string | null
}
export interface EntryPageView { readonly entries: readonly EntryView[]; readonly total: number; readonly offset: number; readonly limit: number; readonly through: number; readonly oldest: number | null; readonly more: boolean | null }
export interface TokenCount { readonly tokens: number; readonly basis: string; readonly how: string }
export interface MeasuredTurn { readonly turn: number; readonly promptTokens: number; readonly grewBy: number | null; readonly since: number | null }
export interface Unavailable { readonly component: string; readonly reason: string }
export interface ToolCost { readonly name: string; readonly characters: number }
export interface Prefix {
    readonly agent: string; readonly model: string; readonly systemPromptCharacters: number; readonly toolCharacters: number
    readonly systemPromptTokens: TokenCount; readonly toolTokens: TokenCount; readonly tools: readonly ToolCost[]; readonly contextLength: number | null
}
export interface ContextView {
    readonly sent: number | null; readonly sentAtTurn: number | null; readonly turns: number; readonly turnsMeasured: number
    readonly measuredTurns: readonly MeasuredTurn[]; readonly systemPromptTokens: TokenCount | null; readonly toolTokens: TokenCount | null
    readonly messageTokens: TokenCount | null; readonly cacheHitRate: number | null; readonly unavailable: readonly Unavailable[]; readonly prefix: Prefix | null
}
export interface ProjectionMessage { readonly role: string; readonly content: string }
export interface ProjectionView { readonly agent: string; readonly turn: number | null; readonly systemBlockAsSent: boolean; readonly messages: readonly ProjectionMessage[] }
export interface ServerAccount { readonly handle: string; readonly enabled: boolean; readonly serverAdmin: boolean; readonly mustChangePassword: boolean; readonly createdAt: string }
export interface AccountCredential { readonly account: ServerAccount; readonly temporaryPassword: string }
export interface ServerSession { readonly id: string; readonly restricted: boolean; readonly createdAt: string; readonly expiresAt: string }
export interface AdminAudit { readonly id: number; readonly occurredAt: string; readonly actor: string; readonly action: string; readonly target: string; readonly enabled: boolean | null; readonly serverAdmin: boolean | null }
export interface AdminAuditPage { readonly entries: readonly AdminAudit[]; readonly before: number }
export interface SessionsRevoked { readonly handle: string }
export interface ServiceAccount { readonly handle: string; readonly enabled: boolean; readonly createdAt: string }
export interface ServiceScope { readonly project: string; readonly role: ProjectRole }
export interface ServiceToken { readonly id: string; readonly name: string; readonly principal: string; readonly createdAt: string; readonly expiresAt: string; readonly revokedAt: string | null; readonly scopes: readonly ServiceScope[] }
export interface ServiceCredential { readonly token: ServiceToken; readonly credential: string }
export interface PricingRates { readonly input: string | null; readonly output: string | null; readonly cacheRead: string | null; readonly cacheWrite: string | null }
export type PricingMode = 'TOKEN' | 'INCLUDED' | 'ZERO_RATE' | 'UNPRICED'
export interface PricingTier { readonly fromInputTokens: number; readonly rates: PricingRates }
export interface PricingCard { readonly revision: string; readonly mode: PricingMode; readonly currency: string | null; readonly rates: PricingRates | null; readonly tiers: readonly PricingTier[]; readonly requestFee: string | null; readonly source: string; readonly validFrom: string | null; readonly validUntil: string | null }
export interface PricingEntry { readonly billingRoute: string; readonly model: string; readonly pools: readonly string[]; readonly version: string; readonly origin: string; readonly card: PricingCard | null; readonly configured: readonly PricingCard[]; readonly updatedAt: string | null }
export interface AdminStatus { readonly handle: string; readonly serverAdmin: boolean }

export interface ConversationReplies {
    'conversation.open': ConversationView
    'conversation.list': readonly ConversationView[]
    'conversation.latest': ConversationView | null
    'conversation.lifecycle': LifecycleView
    'conversation.turns': readonly TurnView[]
    'conversation.compactions': readonly CompactionView[]
    'conversation.chat': EntryPageView
    'conversation.trajectory': EntryPageView
    'conversation.context': ContextView
    'conversation.projection': ProjectionView
    'agent.list': readonly AgentView[]
    'agent.define': DefinedAgent
    'admin.pricing.list': readonly PricingEntry[]
    'admin.pricing.set': PricingEntry
    'admin.status': AdminStatus
    'admin.accounts': readonly ServerAccount[]
    'admin.account.create': AccountCredential
    'admin.account.update': ServerAccount
    'admin.account.reset': AccountCredential
    'admin.sessions': readonly ServerSession[]
    'admin.session.revoke': SessionsRevoked
    'admin.audit': AdminAuditPage
    'admin.service.accounts': readonly ServiceAccount[]
    'admin.service.account.create': ServiceAccount
    'admin.service.account.update': ServiceAccount
    'admin.service.tokens': readonly ServiceToken[]
    'admin.service.token.create': ServiceCredential
    'admin.service.token.rotate': ServiceCredential
    'admin.service.token.revoke': ServiceToken
    'project.list': readonly ProjectView[]
    'project.attach': ProjectView
    'project.create': ProjectView
    'project.define': ProjectView
    'project.lend': ProjectView
    'project.unlend': ProjectView
    'project.workspace': ProjectView
    'project.move': null
    'project.forget': null
    'project.access': ProjectAccess
    'project.member.role': ProjectAccess
    'project.member.add': ProjectMembers
    'project.member.remove': ProjectMembers
}
export type ConversationOperation = keyof ConversationReplies
const conversation = record({ id: named, project: nullable(named), maxModelCalls: nullable(count), modelCallsSpent: nullable(count), maxTurns: nullable(count), noTurnCap: bool, noBudget: bool, title: nullable(text) })
const project = record({ routingIdentity: value => value === undefined || named(value), displayName: value => value === undefined || named(value), name: named, workspace: named, lent: strings, exclusions: strings, machine: nullable(text), members: strings, kind: value => value === undefined || value === 'project' || value === 'personal', type: value => value === undefined || named(value), readOnly: value => value === undefined || bool(value), writePaths: value => value === undefined || strings(value) })
const members = record({ project: named, members: strings })
export const commandEntry = record({ command: named, aliases: strings,
    kind: value => value === 'skill' || value === 'orchestration', name: named, description: text,
    argumentHint: text, executor: named, mode: value => value === null || (typeof value === 'string' && ['INHERITED', 'SUMMARISED', 'NEW', 'DIRECT'].includes(value)),
    tier: named, hash: named, agentVisible: value => value === undefined || bool(value) })
const optional = (check: Check): Check => value => value === undefined || check(value)
const agent = record({ displayName: optional(nullable(named)), origin: optional(nullable(named)), name: named, tools: strings, calls: strings, scopes: strings, served: bool, withheld: strings, bot: bool, description: text, preferred: bool, model: nullable(text), orchestrations: strings, skills: optional(strings), commands: optional(list(commandEntry)) })
const opened = record({ conversation: named, agent: named })
const asked = record({ id: text, name: text, arguments: text, length: count, cut: bool, salient: nullable(text), opened: nullable(opened) })
const entry = record({ ordinal: positive, turnOrdinal: count, kind: named, excerpt: nullable(text), length: count, cut: bool, ejectedAt: nullable(named), supersededBy: nullable(positive),
    toolCallId: nullable(text), toolCalls: list(asked), handle: nullable(text), recordedAt: nullable(named), tookMillis: nullable(count), dispatch: nullable(text), wireModel: nullable(text), completion: nullable(text),
    speaker: nullable(text), speakerName: nullable(text), outcome: nullable(text) })
const page = record({ entries: list(entry), total: count, offset: count, limit: positive, through: count, oldest: nullable(positive), more: nullable(bool) }, row => {
    const entries = row['entries'] as Record<string, unknown>[]
    const ordinals = entries.map(each => each['ordinal'] as number)
    return new Set(ordinals).size === ordinals.length && ordinals.every(ordinal => ordinal <= (row['through'] as number))
        && entries.length <= (row['limit'] as number) && entries.length <= (row['total'] as number)
        && row['oldest'] === (ordinals.length === 0 ? null : Math.min(...ordinals))
})
const tokens = record({ tokens: count, basis: named, how: named })
const measured = record({ turn: positive, promptTokens: positive, grewBy: nullable(integer), since: nullable(positive) })
const prefix = record({ agent: named, model: named, systemPromptCharacters: count, toolCharacters: count, systemPromptTokens: tokens, toolTokens: tokens,
    tools: list(record({ name: text, characters: count })), contextLength: nullable(positive) })
const context = record({ sent: nullable(positive), sentAtTurn: nullable(positive), turns: count, turnsMeasured: count, measuredTurns: list(measured), systemPromptTokens: nullable(tokens), toolTokens: nullable(tokens),
    messageTokens: nullable(tokens), cacheHitRate: nullable(finite), unavailable: list(record({ component: named, reason: text })), prefix: nullable(prefix) })
const serverAccount = record({ handle: named, enabled: bool, serverAdmin: bool, mustChangePassword: bool, createdAt: named })
const credential = record({ account: serverAccount, temporaryPassword: named })
const role: Check = value => ['VIEWER','CONTRIBUTOR','MANAGER'].includes(String(value))
const serviceAccount = record({handle:named,enabled:bool,createdAt:named})
const serviceToken = record({id:named,name:named,principal:named,createdAt:named,expiresAt:named,revokedAt:nullable(named),scopes:list(record({project:named,role}))})
const serviceCredential = record({token:serviceToken,credential:named})
const projectAccess = record({ project: named, role, permissions: strings, members: list(record({handle:named,role})), history: list(record({id:positive,occurredAt:named,actor:named,target:named,action:named,role:nullable(role)})) })
const priceDecimal: Check = value => typeof value === 'string' && /^[0-9]+(\.[0-9]+)?$/.test(value)
const priceRates = record({ input: nullable(priceDecimal), output: nullable(priceDecimal), cacheRead: nullable(priceDecimal), cacheWrite: nullable(priceDecimal) })
const priceCard = record({revision:named,mode:value => ['TOKEN','INCLUDED','ZERO_RATE','UNPRICED'].includes(String(value)),currency:nullable(named),rates:nullable(priceRates),tiers:list(record({fromInputTokens:count,rates:priceRates})),requestFee:nullable(priceDecimal),source:named,validFrom:nullable(named),validUntil:nullable(named)})
const priceEntry = record({billingRoute:named,model:named,pools:strings,version:named,origin:named,card:nullable(priceCard),configured:list(priceCard),updatedAt:nullable(named)})
const readers = {
    'conversation.open': conversation, 'conversation.list': list(conversation), 'conversation.latest': value => noContent(value) || conversation(value),
    'conversation.lifecycle': record({ id: named, lifecycle: named }), 'conversation.turns': list(record({ ordinal: positive, utterance: text, answer: text, ending: named, promptTokens: nullable(positive) })),
    'conversation.compactions': list(record({ throughOrdinal: count, summary: text })), 'conversation.chat': page, 'conversation.trajectory': page, 'conversation.context': context,
    'conversation.projection': record({ agent: named, turn: nullable(positive), systemBlockAsSent: bool, messages: list(record({ role: named, content: text })) }),
    'agent.list': list(agent), 'agent.define': record({ agent, restartRequired: bool }),
    'admin.pricing.list': list(priceEntry), 'admin.pricing.set': priceEntry,
    'admin.status': record({ handle: named, serverAdmin: bool }),
    'admin.accounts': list(serverAccount), 'admin.account.create': credential, 'admin.account.reset': credential,
    'admin.account.update': serverAccount, 'admin.sessions': list(record({ id: named, restricted: bool, createdAt: named, expiresAt: named })),
    'admin.session.revoke': record({ handle: named }),
    'admin.audit': record({ entries: list(record({ id: positive, occurredAt: named, actor: named, action: named, target: named, enabled: nullable(bool), serverAdmin: nullable(bool) })), before: count }),
    'admin.service.accounts': list(serviceAccount), 'admin.service.account.create': serviceAccount, 'admin.service.account.update': serviceAccount,
    'admin.service.tokens': list(serviceToken), 'admin.service.token.create': serviceCredential, 'admin.service.token.rotate': serviceCredential, 'admin.service.token.revoke': serviceToken,
    'project.access': projectAccess, 'project.member.role': projectAccess,
    'project.attach': project, 'project.create': project, 'project.list': list(project), 'project.define': project, 'project.lend': project, 'project.unlend': project, 'project.workspace': project,
    'project.move': noContent, 'project.forget': noContent, 'project.member.add': members, 'project.member.remove': members,
} satisfies Record<ConversationOperation, Check>
export const CONVERSATION_OPERATIONS = Object.keys(readers) as readonly ConversationOperation[]
export function isConversationOperation(type: string): type is ConversationOperation { return Object.hasOwn(readers, type) }
/** Preserve raw replies, including the explicit absence of a latest conversation and CREATED definitions. */
export function conversationReply<T extends ConversationOperation>(type: T, outcome: Outcome): (Outcome & { readonly payload?: ConversationReplies[T] }) | undefined {
    const validCode = type === 'project.move' || type === 'project.forget' ? outcome.code === 'NO_CONTENT'
        : outcome.code === 'OK' || (type === 'agent.define' && outcome.code === 'CREATED')
    return validCode && readers[type](outcome.payload) ? outcome as Outcome & { readonly payload?: ConversationReplies[T] } : undefined
}
