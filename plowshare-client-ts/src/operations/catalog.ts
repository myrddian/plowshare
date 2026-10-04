import type { MessagingPayloads } from './messaging.ts'
import type { UsagePayloads } from './usage.ts'
import type { InformationPayloads } from './information-payloads.ts'
import type { AdministrativePayloads } from './administration.ts'

/** WS request shapes. Optional fields retain the server's defaults. */
type Tier = { readonly project?: string | null }
type Conversation = { readonly conversation: string }
type Page = { readonly offset?: number; readonly limit?: number }
type Turns = { readonly maxTurns?: number; readonly noTurnCap?: boolean }
type Budget = { readonly maxModelCalls?: number }
export interface ExtendedPayloads extends MessagingPayloads, AdministrativePayloads, InformationPayloads, Omit<UsagePayloads, 'usage.subscribe' | 'usage.unsubscribe'> {
    'conversation.open': Tier & Turns & Budget & { readonly noBudget?: boolean }
    'conversation.list': Tier & { readonly lifecycle?: string }
    'conversation.latest': Tier & { readonly agent: string }
    'conversation.lifecycle': Conversation & { readonly lifecycle: string }
    'conversation.turns': Conversation
    'conversation.compactions': Conversation
    'conversation.chat': Conversation & Page
    'conversation.trajectory': Conversation & Page & { readonly after?: number; readonly before?: number; readonly tail?: boolean; readonly kinds?: readonly string[]; readonly drawn?: boolean }
    'conversation.context': Conversation & { readonly agent?: string }
    'conversation.projection': Conversation & { readonly agent?: string; readonly turn?: number }
    'conversation.resume': Conversation & Turns & Budget & { readonly agent?: string; readonly session?: string | null }
    'agent.list': Tier
    'agent.define': Tier & { readonly name: string; readonly text: string; readonly overwrite?: boolean }
    'agent.run': Tier & Turns & { readonly agent: string; readonly task: string; readonly session?: string | null; readonly conversation?: string | null; readonly newConversation?: boolean; readonly images?: readonly string[] }
    'board.topics': Tier & Page
    'board.messages': { readonly topic: string }
    'swarm.status': Record<string, never>
    'job.list': Record<string, never>
    'job.limits': Turns & Budget & { readonly job: string }
    'document.ask': Budget & { readonly document: string; readonly question: string }
    'document.retrieve': { readonly query: string; readonly document?: string | null; readonly limit?: number }
    'document.list': Page & { readonly q?: string }
    'document.detail': { readonly document: string }
    'document.chunk': { readonly chunk: string }
    'document.rank': { readonly query: string; readonly limit?: number }
    'document.stance': { readonly document: string; readonly claim: string }
    'document.citations': { readonly document?: string | null; readonly conversation?: string | null; readonly limit?: number }
    'document.search': { readonly query: string; readonly limit?: number; readonly mode?: string }
    // These three paging values are required by SearchService; it has no defaults.
    'web.search': { readonly query: string; readonly pageSize: number; readonly max: number; readonly page: number }
    'web.fetch': { readonly url: string; readonly offset?: number }
    'admin.pricing.list': Record<string, never>
    'admin.pricing.set': { billingRoute: string; model: string; expectedVersion: string; mode: 'TOKEN' | 'INCLUDED' | 'ZERO_RATE' | 'UNPRICED'; currency?: string; rates?: { input: string; output: string; cacheRead?: string; cacheWrite?: string }; tiers?: readonly { fromInputTokens: number; rates: { input: string; output: string; cacheRead?: string; cacheWrite?: string } }[]; requestFee?: string; source?: string }
    'admin.status': Record<string, never>
    'admin.accounts': Record<string, never>
    'admin.account.create': { handle: string; serverAdmin?: boolean }
    'admin.account.update': { handle: string; enabled?: boolean; serverAdmin?: boolean }
    'admin.account.reset': { handle: string }
    'admin.sessions': { handle: string }
    'admin.session.revoke': { handle: string }
    'admin.audit': { handle?: string; before?: number; limit?: number }
    'admin.service.accounts': Record<string, never>
    'admin.service.account.create': { readonly handle: string }
    'admin.service.account.update': { readonly handle: string; readonly enabled: boolean }
    'admin.service.tokens': { readonly handle: string }
    'admin.service.token.create': { readonly handle: string; readonly name: string; readonly scopes: readonly { readonly project: string; readonly role: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER' }[]; readonly expiresInDays?: number }
    'admin.service.token.rotate': { readonly handle: string; readonly id: string; readonly expiresInDays?: number }
    'admin.service.token.revoke': { readonly handle: string; readonly id: string }
    'project.list': Record<string, never>
    'project.attach': { readonly name: string; readonly workspace: string; readonly machine: string }
    'project.create': { readonly name: string; readonly workspace?: string; readonly type?: string; readonly writePaths?: readonly string[] }
    'project.define': { readonly name: string; readonly workspace: string; readonly lent?: readonly string[]; readonly exclusions?: readonly string[] }
    'project.lend': { readonly project: string; readonly roots: readonly string[] }
    'project.unlend': { readonly project: string; readonly roots: readonly string[] }
    'project.workspace': { readonly project: string; readonly workspace: string }
    'project.move': { readonly project: string; readonly to: string }
    'project.forget': { readonly project: string }
    'project.access': { readonly project: string }
    'project.member.role': { readonly project: string; readonly handle: string; readonly role: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER' }
    'project.member.add': { readonly project: string; readonly handle: string; readonly role?: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER' }
    'project.member.remove': { readonly project: string; readonly handle: string }
}

/** Explicit operator verbs; no arbitrary frame forwarding or local filesystem claims. */
export const CLI_OPERATIONS = {
    'outgoing send': 'outgoing.send', 'outgoing status': 'outgoing.status',
    'outgoing cancel': 'outgoing.cancel', 'outgoing peers': 'outgoing.peers',
    'usage conversation': 'usage.conversation', 'usage project': 'usage.project',
    'usage agent': 'usage.agent', 'usage run': 'usage.run', 'usage orchestration': 'usage.orchestration',
    'usage models': 'usage.models', 'usage pools': 'usage.pools', 'usage calls': 'usage.calls',
    'context snapshot': 'conversation.context.snapshot',
    'context count': 'conversation.context.count',
    'information upload': 'information.upload',
    'information acquire': 'information.acquire',
    'information facets': 'information.facets',
    'information tags.groups': 'information.tags.groups',
    'information tags': 'information.tags',
    'information list': 'information.list',
    'information status': 'information.status',
    'information await': 'information.await',
    'information read': 'information.read',
    'information outline': 'information.outline',
    'information symbols': 'information.symbols',
    'information search': 'information.search',
    'information rank': 'information.rank',
    'information ask': 'information.ask',
    'information evidence.record': 'information.evidence.record',
    'information evidence.read': 'information.evidence.read',
    'information record.report': 'information.record.report',
    'information finalise': 'information.finalise',
    'information link': 'information.link',
    'information unlink': 'information.unlink',
    'information share': 'information.share',
    'information unshare': 'information.unshare',
    'information withdraw': 'information.withdraw',
    'information exclude': 'information.exclude',
    'information unexclude': 'information.unexclude',
    'information restore': 'information.restore',
    'information delete': 'information.delete',
    'information retry': 'information.retry',
    'information revise': 'information.revise',
    'information replace': 'information.replace',
    'information refresh': 'information.refresh',
    'information rebuild': 'information.rebuild',
    'information allowance': 'information.allowance',
    'information events': 'information.events',
    'information migration.list': 'information.migration.list',
    'information migration.adopt': 'information.migration.adopt',
    'information migration.inspect': 'information.migration.inspect',
    'information migration.release': 'information.migration.release',
    'information inventory': 'information.inventory',
    'information acquisitions': 'information.acquisitions',

    'conversation open': 'conversation.open', 'conversation list': 'conversation.list',
    'conversation latest': 'conversation.latest', 'conversation lifecycle': 'conversation.lifecycle',
    'conversation turns': 'conversation.turns', 'conversation compactions': 'conversation.compactions',
    'conversation chat': 'conversation.chat', 'conversation trajectory': 'conversation.trajectory',
    'conversation context': 'conversation.context', 'conversation projection': 'conversation.projection',
    'conversation resume': 'conversation.resume', 'agent list': 'agent.list',
    'agent define': 'agent.define', 'agent run': 'agent.run', 'job list': 'job.list', 'job limits': 'job.limits',
    'document ask': 'document.ask', 'document retrieve': 'document.retrieve', 'document list': 'document.list',
    'document detail': 'document.detail', 'document outline': 'document.detail', 'document chunk': 'document.chunk',
    'document rank': 'document.rank', 'document stance': 'document.stance', 'document citations': 'document.citations',
    'document search': 'document.search', 'web search': 'web.search', 'web fetch': 'web.fetch',
    'admin pricing list': 'admin.pricing.list',
    'admin pricing set': 'admin.pricing.set',
    'admin status': 'admin.status',
    'admin accounts': 'admin.accounts',
    'admin account create': 'admin.account.create',
    'admin account update': 'admin.account.update',
    'admin account reset': 'admin.account.reset',
    'admin sessions': 'admin.sessions',
    'admin session revoke': 'admin.session.revoke',
    'admin audit': 'admin.audit',
    'admin service accounts': 'admin.service.accounts',
    'admin service account create': 'admin.service.account.create',
    'admin service account update': 'admin.service.account.update',
    'admin service tokens': 'admin.service.tokens',
    'admin service token create': 'admin.service.token.create',
    'admin service token rotate': 'admin.service.token.rotate',
    'admin service token revoke': 'admin.service.token.revoke',


    'project attach': 'project.attach', 'project create': 'project.create', 'project list': 'project.list', 'project define': 'project.define', 'project lend': 'project.lend',
    'project unlend': 'project.unlend', 'project workspace': 'project.workspace', 'project move': 'project.move',
    'project access': 'project.access', 'project member-role': 'project.member.role', 'project forget': 'project.forget', 'project member-add': 'project.member.add', 'project member-remove': 'project.member.remove',
    'approval list': 'approval.list', 'approval answer': 'approval.answer', 'approval revoke': 'approval.revoke',
    'message instances': 'message.instances', 'message instance': 'message.instance',
    'message open': 'message.instance.open', 'message default': 'message.instance.default',
    'message stop': 'message.instance.stop', 'message archive': 'message.instance.archive',
    'message deliveries': 'message.deliveries', 'message delivery': 'message.delivery', 'message cancel': 'message.cancel',
    'board topics': 'board.topics', 'board messages': 'board.messages', 'swarm status': 'swarm.status',
    'board topup': 'board.topup', 'board retry': 'board.retry', 'board post': 'board.post', 'board open': 'board.open', 'buffer purge': 'buffer.purge', 'retention sweep': 'retention.sweep',
    'provider list': 'provider.list', 'provider deregister': 'provider.deregister',
    'inbox list': 'inbox.list', 'inbox read': 'inbox.read', 'todos read': 'todos.read',
    'orchestration start': 'orchestration.start', 'orchestration receipt': 'orchestration.receipt',
    'orchestration definitions': 'orchestration.definitions', 'orchestration list': 'orchestration.list',
    'orchestration status': 'orchestration.status', 'orchestration answer': 'orchestration.answer',
    'orchestration cancel': 'orchestration.cancel', 'orchestration caps': 'orchestration.caps',
    'orchestration record': 'orchestration.record', 'schedule list': 'schedule.list',
    'schedule define': 'schedule.define', 'schedule read': 'schedule.read', 'schedule pause': 'schedule.pause',
    'schedule forget': 'schedule.forget', 'trigger list': 'trigger.list', 'trigger define': 'trigger.define',
    'trigger pause': 'trigger.pause', 'trigger forget': 'trigger.forget', 'event fire': 'event.fire',
    'firing list': 'firing.list', 'union status': 'union.status', 'union conflicts': 'union.conflict.list',
} as const satisfies Record<string, keyof ExtendedPayloads>
