import type { InformationScope } from './information.ts'

/** Authenticated information frame payloads; publication and migration remain operator controls. */
export interface InformationPayloads {
    'information.upload': { readonly scope: InformationScope; readonly requestId: string; readonly name: string; readonly text: string; }
    'information.acquire': { readonly scope: InformationScope; readonly requestId: string; readonly url: string; readonly name?: string; }
    'information.list': { readonly scope: InformationScope; readonly limit?: number; readonly offset?: number; }
    'information.status': { readonly scope: InformationScope; readonly revision?: string; readonly acquisition?: string; }
    'information.read': { readonly scope: InformationScope; readonly revision: string; readonly offset?: number; readonly limit?: number; }
    'information.search': { readonly scope: InformationScope; readonly query: string; readonly revision?: string; readonly limit?: number; }
    'information.rank': { readonly scope: InformationScope; readonly query: string; readonly limit?: number; }
    'information.ask': { readonly scope: InformationScope; readonly revision: string; readonly question: string; readonly maxModelCalls?: number; }
    'information.evidence.record': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly start: number; readonly end: number; readonly quote: string; readonly locator: string; }
    'information.evidence.read': { readonly scope: InformationScope; readonly evidence: string; }
    'information.record.report': { readonly scope: InformationScope; readonly requestId: string; readonly name: string; readonly text: string; readonly inputs?: readonly string[]; readonly evidence?: readonly string[]; readonly feedback?: string; readonly objectives?: readonly string[]; readonly findings?: readonly Readonly<Record<string, unknown>>[]; readonly reviews?: readonly Readonly<Record<string, unknown>>[]; readonly scopeChanges?: readonly string[]; }
    'information.finalise': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.link': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly collectionProject: string; }
    'information.unlink': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly collectionProject: string; }
    'information.share': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.unshare': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.withdraw': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.exclude': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.unexclude': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.restore': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.delete': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.retry': { readonly scope: InformationScope; readonly acquisition?: string; readonly revision?: string; readonly requestId?: string; }
    'information.revise': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly text: string; readonly name?: string; }
    'information.replace': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly text: string; readonly name?: string; }
    'information.refresh': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; }
    'information.rebuild': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly stage: string; }
    'information.allowance': { readonly scope: InformationScope; readonly revision: string; readonly requestId: string; readonly maxModelCalls: number; }
    'information.events': { readonly scope: InformationScope; readonly after?: number; readonly limit?: number; }
    'information.migration.list': { readonly scope?: InformationScope; readonly limit?: number; readonly offset?: number; }
    'information.migration.adopt': { readonly scope?: InformationScope; readonly revision: string; readonly owner: string; readonly visibility: string; readonly reason: string; readonly requestId: string; readonly collectionProject?: string; }
    'information.migration.inspect': { readonly scope?: InformationScope; readonly payload: string; readonly reason: string; readonly limit?: number; readonly offset?: number; }
    'information.migration.release': { readonly scope: InformationScope; readonly payload: string; readonly owner: string; readonly reason: string; readonly requestId: string; readonly inputs?: readonly string[]; }
    'information.inventory': { readonly scope: InformationScope; readonly limit?: number; readonly offset?: number; }
    'information.acquisitions': { readonly scope: InformationScope; readonly limit?: number; readonly offset?: number; }
}
