import { createHash } from 'node:crypto';
import { hostedReplyMatches } from '../sdk/typescript/build/operations/hosted-tool-scopes.js';
/** Shared boundary examples, checked against the canonical SDK before native tests. */
import {
  decodePayload,
  decodeReply,
} from '../sdk/typescript/build/operations/schema.js';
import { decodeServerPush } from '../sdk/typescript/build/operations/push.js';
import { readFileSync, writeFileSync } from 'node:fs';
const cases = [];
const input = (operation, value, valid) =>
  cases.push({ boundary: 'input', operation, value, valid });
const result = (operation, value, valid) =>
  cases.push({ boundary: 'result', operation, value, valid });
const push = (value, valid) => cases.push({ boundary: 'push', value, valid });
// Approval reasons are optional on the server, including ordinary local commands.
const approvalQuestion = {id:'approval-fixture',conversation:'conversation-fixture',askedIn:'conversation-fixture',agent:'worker',side:'local',command:['echo','fixture'],cwd:'/fixture',reason:null,state:'asked',scope:null,prefix:null,defaultPrefix:['echo'],createdAt:'2026-10-17T00:00:00Z',answeredAt:null,commands:null,judged:null};
for (const reason of [null, '', 'Review the command']) result('approval.list',{approvals:[{...approvalQuestion,reason}]},true);
for (const reason of [false, 1, [], {}]) result('approval.list',{approvals:[{...approvalQuestion,reason}]},false);
input('approval.answer', {id:'approval-fixture',decision:'deny',reason:'Publishing is unnecessary'}, true);
for (const reason of ['', '  ', null, 5, 'x'.repeat(4001), 'bad\0reason'])
  input('approval.answer', {id:'approval-fixture',decision:'deny',reason}, false);
input('approval.answer', {id:'approval-fixture',decision:'once',reason:'not a denial'}, false);

const receipt = '11111111-1111-1111-1111-111111111111';

const portableBody=Buffer.from('Hello 🔎\r\n');
const portableHash=createHash('sha256').update(portableBody).digest('hex');
const portableDescriptor={resource:receipt,revision:receipt,ordinal:1,sourceName:'source.md',title:'Source',path:'documents/source.md',mediaType:'text/markdown',contentHash:portableHash,tags:['billing'],provenance:{owner:'owner',kind:'source',createdAt:'2026-10-16T00:00:00Z',sourceHash:portableHash,dependencies:[],evidence:[]}};
const portableBundle={format:'plowshare-information-bundle/1',id:receipt,documents:[{descriptor:portableDescriptor,bytesBase64:portableBody.toString('base64')}],relationships:[],relationshipCoverage:'included-endpoints-only'};
const portablePlan={bundle:portableBundle,destinations:[{resource:receipt,name:'imported/source.md',mode:'create'}],types:[],reuse:[],restoreRelationships:false};
const portableImport={scope:{kind:'personal'},requestId:receipt,plan:portablePlan};
input('information.interchange.import',portableImport,true);
const portableEditedPin={...portableBundle,documents:[{...portableBundle.documents[0],descriptor:{...portableDescriptor,provenance:{...portableDescriptor.provenance,sourceHash:'0'.repeat(64)}}}],relationships:[{source:receipt,target:receipt,pinnedRevision:receipt,relation:'reference'}]};
input('information.interchange.import',{...portableImport,plan:{...portablePlan,bundle:portableEditedPin,restoreRelationships:true}},false);
input('information.interchange.import',{...portableImport,plan:{...portablePlan,bundle:portableEditedPin,restoreRelationships:false}},true);

const portableNext='22222222-2222-4222-8222-222222222222';
const reusedBundle={...portableBundle,documents:[...portableBundle.documents,{descriptor:{...portableDescriptor,revision:portableNext,ordinal:2,path:'documents/next.md',provenance:{...portableDescriptor.provenance,dependencies:[receipt]}},bytesBase64:portableBody.toString('base64')}]};
input('information.interchange.import',{...portableImport,plan:{...portablePlan,bundle:reusedBundle,destinations:[{resource:receipt,name:'imported/source.md',mode:'revise',expectedRevision:portableNext}],reuse:[{foreignRevision:receipt,localRevision:receipt}]}},true);

const portableReadable={format:portableBundle.format,path:portableDescriptor.path,representation:'original_markdown',markdown:portableBody.toString('utf8'),textHash:portableHash,sidecar:portableDescriptor};
result('information.interchange.readable',portableReadable,true);
result('information.interchange.readable',{...portableReadable,textHash:'0'.repeat(64)},false);
for(const markdown of ['', ' \r\n', '\uFEFF# Safety 🔎\r\n', 'é e\u0301 漢字']) {
  const textHash=createHash('sha256').update(markdown,'utf8').digest('hex');
  result('information.interchange.readable',{...portableReadable,markdown,textHash},true);
  result('information.interchange.readable',{...portableReadable,markdown,textHash:'0'.repeat(64)},false);
}
result('information.interchange.export',portableBundle,true);
input('information.interchange.export',{scope:{kind:'personal'},revisions:[receipt]},true);
for(const revisions of [[],['bad'],Array(17).fill(receipt),[receipt,receipt]]) input('information.interchange.export',{scope:{kind:'personal'},revisions},false);
for(const path of ['/absolute.md','../source.md','safe/../source.md','safe//source.md','safe/CON.md','safe/.git/config','safe/nul','file.','safe/']) input('information.interchange.import',{...portableImport,plan:{...portablePlan,bundle:{...portableBundle,documents:[{...portableBundle.documents[0],descriptor:{...portableDescriptor,path}}]}}},false);
for(const bytesBase64 of ['', 'QQ','QR==','QQ==\n']) input('information.interchange.import',{...portableImport,plan:{...portablePlan,bundle:{...portableBundle,documents:[{descriptor:portableDescriptor,bytesBase64}]}}},false);
for(const changed of [{destinations:[]},{destinations:[{resource:receipt,name:'source.md',mode:'revise'}]},{destinations:[{resource:receipt,name:'source.md',mode:'create',expectedRevision:receipt}]},{reuse:[{foreignRevision:'bad',localRevision:receipt}]},{reuse:[{foreignRevision:receipt,localRevision:receipt}],destinations:[]},{restoreRelationships:'yes'}]) input('information.interchange.import',{...portableImport,plan:{...portablePlan,...changed}},false);
input('information.interchange.import',{...portableImport,scope:{kind:'global_read'}},false);
input('information.interchange.import',{...portableImport,plan:{...portablePlan,permissions:['admin']}},false);
input('information.interchange.import',{...portableImport,plan:{...portablePlan,bundle:{...portableBundle,documents:[{descriptor:{...portableDescriptor,provenance:{...portableDescriptor.provenance,dependencies:['22222222-2222-4222-8222-222222222222']}},bytesBase64:portableBody.toString('base64')}]}}},false);

const navScope={kind:'personal'};
const navPolicy={ownerOnly:false,referenceHidden:true,propagate:true};
const navSettings={defaults:navPolicy,mandatory:{ownerOnly:false,referenceHidden:false,propagate:false},allowOverrides:true,referenceLabel:'Restricted document'};
const navSave={scope:navScope,requestId:receipt,id:receipt,expectedVersion:0,label:'Billing',path:{steps:[{kind:'scope',scope:navScope},{kind:'document',target:{resource:receipt}}]}};
input('information.views.save',navSave,true);
for(const changed of [{expectedVersion:-1},{expectedVersion:1.5},{id:'short'},{path:{steps:[]}},{path:{steps:Array(33).fill({kind:'scope',scope:navScope})}},{path:{steps:[{kind:'document',target:{resource:receipt}}]}},{path:{steps:[{kind:'scope',scope:navScope},{kind:'scope',scope:navScope}]}},{path:{steps:[{kind:'scope',scope:navScope},{kind:'traverse',relation:'../private',direction:'outgoing'}]}},{label:'x'.repeat(121)},{extra:true}])input('information.views.save',{...navSave,...changed},false);
input('information.documents.list',{scope:navScope},true);
input('information.documents.list',{scope:navScope,relation:'runbook'},false);
input('information.documents.list',{scope:navScope,limit:101},false);
const metadataRequest={scope:navScope,view:'content-types-v1',projection:'metadata-v1',limit:50};
input('information.documents.list',metadataRequest,true);
for(const change of [{view:undefined},{projection:null},{projection:'body'},{limit:101}]) {
  const request={...metadataRequest,...change};
  if(request.view===undefined) delete request.view;
  input('information.documents.list',request,false);
}
const metadataDocument={resource:receipt,revision:receipt,title:'Discovery',sourceName:'fixture.md',owner:'fixture',tags:[],scope:navScope,
  contentMetadata:{type:{type:receipt,version:receipt,ordinal:1,slug:'incident',label:'Incident',definitionHash:'a'.repeat(64)},metadataHash:'b'.repeat(64),metadata:{title:'Typed title',note:null}}};
const metadataPage={documents:[metadataDocument],limit:50,offset:0,projection:'metadata-v1'};
result('information.documents.list',metadataPage,true);
result('information.documents.list',{...metadataPage,documents:[]},true);
const {contentMetadata,...legacyDocument}=metadataDocument;
result('information.documents.list',{documents:[legacyDocument],limit:50,offset:0},true);
for(const changed of [
  {...contentMetadata,metadataHash:'bad'},
  {...contentMetadata,metadata:'text'},
  {...contentMetadata,metadata:{title:'x'.repeat(4097)}},
  {...contentMetadata,type:{...contentMetadata.type,version:'not-a-uuid'}},
  {...contentMetadata,type:{...contentMetadata.type,ordinal:0}},
]) result('information.documents.list',{...metadataPage,documents:[{...metadataDocument,contentMetadata:changed}]},false);
input('information.documents.get',{scope:navScope,target:{resource:receipt,revision:receipt}},true);
input('information.documents.get',{scope:navScope,target:{resource:receipt,revision:null}},false);
input('information.documents.get',{scope:navScope,target:{resource:'short'}},false);
const navSet={scope:navScope,requestId:receipt,target:{kind:'platform'},expectedVersion:0,settings:navSettings,reason:'Initial policy'};
input('information.policy.set',navSet,true);
input('information.policy.set',{...navSet,target:{kind:'project'}},false);
input('information.policy.set',{...navSet,settings:{...navSettings,defaults:{ownerOnly:false}}},false);
input('information.policy.set',{...navSet,settings:{...navSettings,referenceLabel:'x'.repeat(121)}},false);
input('information.relationships.create',{scope:navScope,requestId:receipt,source:receipt,target:{resource:receipt},relation:'runbook',expectedSourceRevision:receipt},true);
input('information.relationships.remove',{scope:navScope,requestId:receipt,relationship:receipt,expectedVersion:0},false);
result('information.documents.get',{kind:'locked',resource:receipt,label:'Approved label'},true);
result('information.documents.get',{kind:'unavailable'},true);
result('information.documents.get',{kind:'locked',resource:receipt},false);
result('information.documents.get',{kind:'readable'},false);

const authored={scope:{kind:'personal'},view:'content-types-v1',requestId:receipt,expectedRevision:receipt,typing:{mode:'inherit',metadata:{severity:null}}};
input('information.content.revise',authored,true);
for(const change of [{view:'foreign-profile'},{typing:{mode:'clear',metadata:{}}},{typing:{mode:'set',version:receipt}},{typing:{mode:'inherit',metadata:[]}},{typing:{mode:'inherit',metadata:{secret:'x'.repeat(4097)}}}])input('information.content.revise',{...authored,...change},false);
input('information.retry',{scope:{kind:'personal'},view:'content-types-v1',revision:receipt,requestId:receipt,stages:['derive','embed']},true);
input('information.retry',{scope:{kind:'personal'},view:'content-types-v1',revision:receipt,requestId:receipt,stages:['embed','embed']},false);
input('information.types.version.create',{scope:{kind:'personal'},view:'content-types-v1',requestId:receipt,slug:'incident',label:'Incident',description:'',schema:{type:'object',properties:{severity:{type:'string'}},required:['severity'],additionalProperties:false}},true);
input('information.types.version.create',{scope:{kind:'personal'},view:'content-types-v1',requestId:receipt,slug:'incident',label:'Incident',schema:{type:'object','$ref':'https://schemas.example/type'}},false);
for (const key of ['__proto__','prototype','constructor']) {
 input('information.content.revise',{...authored,typing:{mode:'inherit',metadata:{nested:{[key]:'value'}}}},false);
 input('information.types.version.create',{scope:{kind:'personal'},view:'content-types-v1',requestId:receipt,slug:'incident',label:'Incident',schema:{type:'object',properties:{[key]:{type:'string'}}}},false);
}
let nested={leaf:'value'};
for(let i=0;i<31;i++)nested={nested};
input('information.types.validate',{scope:{kind:'personal'},view:'content-types-v1',version:receipt,metadata:nested},true);
for(let i=0;i<2;i++)nested={nested};
input('information.types.validate',{scope:{kind:'personal'},view:'content-types-v1',version:receipt,metadata:nested},false);

const providerDefinition={"name": "Fixture", "endpoint": "https://provider.example/mcp", "authentication": "API_KEY", "apiKeyHeader": "X-API-Key", "oauth": null};
const providerConnection={"connection": "11111111-1111-1111-1111-111111111111", "project": "app", "revision": "11111111-1111-1111-1111-111111111111", "enabled": true, "deleted": false, "definition": {"name": "Fixture", "endpoint": "https://provider.example/mcp", "authentication": "API_KEY", "apiKeyHeader": "X-API-Key", "oauth": null}};
const providerGrant={"connection": "11111111-1111-1111-1111-111111111111", "project": "app", "subject": "owner", "generation": "11111111-1111-1111-1111-111111111111", "connectionRevision": "11111111-1111-1111-1111-111111111111", "state": "READY", "expiresAt": null, "keyId": "fixture"};
const providerSave={project:'app',connection:receipt,requestId:receipt,expectedRevision:null,definition:providerDefinition};
input('provider.connection.save',providerSave,true);
for(const change of [{connection:'bad'},{requestId:'bad'},{expectedRevision:'bad'},{project:'personal:owner'},{extra:1}]) input('provider.connection.save',{...providerSave,...change},false);
for(const change of [{endpoint:'http://provider.example/mcp'},{endpoint:'https://evil@provider.example/mcp'},{endpoint:'https://provider.example:99999/mcp'},{endpoint:'https://provider.example/mcp?query=1'},{name:' '},{oauth:{issuer:'https://issuer.example'}}]) input('provider.connection.save',{...providerSave,definition:{...providerDefinition,...change}},false);
const providerPut={project:'app',connection:receipt,subject:null,requestId:receipt,expectedGeneration:null,secret:'fixture-key'};
input('provider.credential.put',providerPut,true);
for(const change of [{subject:'someone'},{subject:'@service/bad'},{secret:'pss_'+'1'.repeat(48)},{secret:'bad\nkey'},{secret:1},{expectedGeneration:'bad'}]) input('provider.credential.put',{...providerPut,...change},false);
result('provider.connection.status',providerConnection,true);
result('provider.connection.status',{...providerConnection,deleted:true,enabled:true},false);
result('provider.credential.status',providerGrant,true);
result('provider.credential.status',{...providerGrant,state:'NONE'},false);
result('provider.credential.status',{...providerGrant,generation:null},false);
result('provider.credential.status',{...providerGrant,keyId:null},false);
const providerReceipt={requestId:receipt,operation:'provider.credential.put',connection:providerConnection,credential:providerGrant};
result('provider.credential.put',providerReceipt,true);
result('provider.credential.put',{...providerReceipt,credential:{...providerGrant,project:'foreign'}},false);
const hostDefinition={scope:'scans',provider:'scanner',prefix:'network_',grants:['*'],agents:['coordinator'],leaseSeconds:30,credentialProject:'app',connection:receipt,subject:null,executor:'@service/'+receipt};
const hostBinding={binding:receipt,project:'personal:owner',owner:'owner',generation:1,enabled:true,deleted:false,application:false,definition:hostDefinition,credentialSubject:'owner',route:{project:'personal:owner',scope:'scans',sourceProvider:'scanner',provider:'host-'+createHash('sha256').update(receipt+'/1').digest('hex').slice(0,32),account:hostDefinition.executor,prefix:'network_',grants:['*'],agents:['coordinator'],leaseSeconds:30}};
const hostSave={project:hostBinding.project,binding:receipt,requestId:receipt,expectedGeneration:null,definition:hostDefinition};
const hostWorker={binding:receipt,registration:receipt};
const hostRegistration={binding:hostBinding,registration:receipt,registeredAt:'2026-10-13T00:00:00Z',expiresAt:'2026-10-13T00:00:30Z'};
input('tool.scope.host.save',hostSave,true);
for(const changed of [{binding:'bad'},{requestId:'bad'},{expectedGeneration:0},{expectedGeneration:'1'},{project:'client:fixture'},{extra:1}])input('tool.scope.host.save',{...hostSave,...changed},false);
for(const changed of [{executor:'owner'},{subject:'other'},{leaseSeconds:301},{agents:[]},{grants:['*','network_read']},{credentialProject:'personal:owner'},{extra:1}])input('tool.scope.host.save',{...hostSave,definition:{...hostDefinition,...changed}},false);
input('tool.scope.host.change',{project:hostBinding.project,binding:receipt,requestId:receipt,expectedGeneration:1,action:'DISABLE'},true);
input('tool.scope.host.list',{project:hostBinding.project},true);
input('tool.scope.host.status',{project:hostBinding.project,binding:receipt},true);
input('tool.scope.host.receipt',{project:hostBinding.project,requestId:receipt},true);
input('tool.scope.worker.register',{binding:receipt,requestId:receipt,expectedGeneration:1},true);
input('tool.scope.worker.renew',hostWorker,true);
input('tool.scope.worker.status',{binding:receipt},true);
input('tool.scope.worker.credential',{...hostWorker,purpose:'DISCOVERY',invocation:null},true);
input('tool.scope.worker.credential',{...hostWorker,purpose:'EXECUTION',invocation:receipt},true);
for(const changed of [{purpose:'DISCOVERY',invocation:receipt},{purpose:'EXECUTION',invocation:null},{owner:'other'},{registration:'bad'}])input('tool.scope.worker.credential',{...hostWorker,purpose:'DISCOVERY',invocation:null,...changed},false);
input('tool.scope.worker.read',{...hostWorker,invocation:receipt},true);
const hostPublication={requestId:receipt,project:hostBinding.project,topic:'tool.'+hostBinding.route.provider+'.catalog',text:'fixture',occurredAt:'2026-10-13T00:00:00Z',correlationId:null,parentTopic:null,parentEventId:null};
input('tool.scope.worker.publish',{...hostWorker,publication:hostPublication},true);
input('tool.scope.worker.publish',{...hostWorker,publication:{...hostPublication,extra:1}},false);
const hostConsumption={project:hostBinding.project,topic:'tool.'+hostBinding.route.provider+'.network_read.request',group:'tool-provider',consumerId:receipt,start:'OLDEST_RETAINED',limit:1,waitMs:0};
input('tool.scope.worker.consume',{...hostWorker,consumption:hostConsumption},true);
input('tool.scope.worker.consume',{...hostWorker,consumption:{...hostConsumption,limit:0}},false);
const hostAck={project:hostBinding.project,topic:hostConsumption.topic,group:'tool-provider',consumerId:receipt,batchId:receipt,fence:'1',expiredThrough:null};
input('tool.scope.worker.ack',{...hostWorker,acknowledgement:hostAck},true);
result('tool.scope.host.status',hostBinding,true);
for(const changed of [{generation:0},{enabled:true,deleted:true},{credentialSubject:'other'}])result('tool.scope.host.status',{...hostBinding,...changed},false);
result('tool.scope.host.status',{...hostBinding,route:{...hostBinding.route,project:'foreign'}},false);
result('tool.scope.host.list',[hostBinding],true);
const hostReceipt={requestId:receipt,operation:'tool.scope.host.save',binding:hostBinding};
result('tool.scope.host.save',hostReceipt,true);result('tool.scope.host.receipt',hostReceipt,true);
result('tool.scope.host.change',{...hostReceipt,operation:'tool.scope.host.change',binding:{...hostBinding,generation:2,enabled:false,route:{...hostBinding.route,provider:'host-'+createHash('sha256').update(receipt+'/2').digest('hex').slice(0,32)}}},true);
result('tool.scope.worker.register',hostRegistration,true);result('tool.scope.worker.renew',hostRegistration,true);
result('tool.scope.worker.status',{binding:receipt,registration:hostRegistration},true);
result('tool.scope.worker.status',{binding:receipt,registration:null},true);
const hostCredential={...hostWorker,purpose:'EXECUTION',invocation:receipt,generation:1,connection:providerConnection,subject:'owner',credentialGeneration:receipt,header:'X-API-Key',value:'fixture-secret'};
result('tool.scope.worker.credential',hostCredential,true);
result('tool.scope.worker.credential',{...hostCredential,value:'bad\nkey'},false);
result('tool.scope.worker.credential',{...hostCredential,purpose:'DISCOVERY'},false);
result('tool.scope.worker.read',{...hostWorker,invocation:receipt,verified:true,state:'COMPLETED',text:'done'},true);
result('tool.scope.worker.publish',{requestId:receipt,project:hostBinding.project,topic:hostPublication.topic,position:'1',publishedAt:'2026-10-13T00:00:00Z'},true);
result('tool.scope.worker.consume',{project:hostConsumption.project,topic:hostConsumption.topic,group:hostConsumption.group,consumerId:hostConsumption.consumerId,status:'EMPTY',batchId:null,fence:null,through:'0',expiresAt:null,expiredThrough:null,events:[]},true);
result('tool.scope.worker.ack',{project:hostBinding.project,topic:hostConsumption.topic,group:'tool-provider',batchId:receipt,through:'1',gap:false},true);
const providerOAuth={issuer:'https://issuer.example',resource:'https://provider.example/mcp',resourceMetadataUrl:'https://provider.example/.well-known/oauth-protected-resource/mcp',issuerMetadataUrl:'https://issuer.example/.well-known/oauth-authorization-server',authorizationEndpoint:'https://issuer.example/authorize',tokenEndpoint:'https://issuer.example/token',clientId:'fixture',redirectUri:'https://plowshare.example/provider-credentials/oauth/callback',scopes:['tools']};
const providerOAuthDefinition={...providerDefinition,authentication:'OAUTH_PKCE',apiKeyHeader:null,oauth:providerOAuth};
input('provider.connection.save',{...providerSave,definition:providerOAuthDefinition},true);
for(const change of [{scopes:['tools','tools']},{scopes:['bad scope']},{clientId:1},{tokenEndpoint:'https://issuer.example/token#frag'},{redirectUri:'https://plowshare.example/callback%2f'}]) input('provider.connection.save',{...providerSave,definition:{...providerOAuthDefinition,oauth:{...providerOAuth,...change}}},false);

const originEntry = {
  ordinal: 1, turnOrdinal: 1, kind: 'utterance', excerpt: 'message', length: 7,
  cut: false, ejectedAt: null, supersededBy: null, toolCallId: null, toolCalls: [],
  handle: null, recordedAt: null, tookMillis: null, dispatch: null, wireModel: null,
  completion: null, speaker: 'harness', speakerName: 'message msg_fixture', outcome: null,
  job: 'job_fixture', source: { kind: 'message', reference: 'msg_fixture' },
};
const originPage = (entry) => ({ entries: [entry], total: 1,
  offset: 0, limit: 1, through: 1, oldest: 1, more: false });
result('conversation.trajectory', originPage(originEntry), true);
result('conversation.trajectory', originPage({ ...originEntry, job: null, source: { kind: 'unknown', reference: null } }), true);
for (const source of [{ kind: 'message', reference: null }, { kind: 'unknown', reference: 'msg_fixture' }, { kind: 'forged', reference: 'msg_fixture' }, { kind: 'message', reference: ' ' }])
  result('conversation.trajectory', originPage({ ...originEntry, source }), false);
result('conversation.trajectory', originPage({ ...originEntry, job: ' ' }), false);
// Provider usage is additive: unknown differs from a measured zero in every SDK.
for (const tokens of [null, 0, 1200])
  result('conversation.trajectory', originPage({ ...originEntry, promptTokens: tokens, completionTokens: tokens, reasoningTokens: tokens }), true);
for (const key of ['promptTokens', 'completionTokens', 'reasoningTokens'])
  for (const tokens of [-1, 1.5, '12'])
    result('conversation.trajectory', originPage({ ...originEntry, [key]: tokens }), false);

const messageDelivery = { message: 'msg_fixture', sender: 'sender', recipient: 'recipient',
  replyTo: null, replyExpected: false, finalReply: false, generated: false, state: 'handled',
  ending: 'ANSWERED', reply: null, job: 'job_fixture', deadlineAt: null,
  postedAt: '2026-10-07T00:00:00Z', body: 'hello', conversation: 'cnv_recipient', sourceConversation: 'cnv_sender' };
result('message.delivery', messageDelivery, true);
result('message.delivery', { ...messageDelivery, conversation: '' }, false);
const actingTurn={requestId:receipt,agent:'chat',task:'Hello'};
input('acting.turn.start',actingTurn,true);
input('acting.turn.start',{...actingTurn,conversation:'cnv_ABC123'},true);
for(const change of [{requestId:'bad'},{agent:'bad name'},{task:' '},{conversation:'other'},{session:'bridge-files'},{project:'research'}]) input('acting.turn.start',{...actingTurn,...change},false);
input('acting.turn.receipt',{requestId:receipt},true);
input('acting.turn.receipt',{requestId:'bad'},false);
const turnReceipt={requestId:receipt,agent:'chat',conversation:'cnv_ABC123',state:'STARTED',job:'job_ABC123'};
result('acting.turn.start',turnReceipt,true);
for(const change of [{requestId:'bad'},{conversation:'other'},{state:'REPLAY'},{job:'other'},{job:null}]) result('acting.turn.receipt',{...turnReceipt,...change},false);
const actingOpen={application:'bridge',grant:'users',proof:{kind:'acting-token',credential:'psa_'+'a'.repeat(48)}};
input('acting.context.open',actingOpen,true);
for(const change of [{application:'personal:alice'},{grant:'bad name'},{proof:{kind:'oidc',credential:'secret'}},{proof:{kind:'acting-token',credential:'bad'}},{proof:{...actingOpen.proof,extra:1}},{extra:1}]) input('acting.context.open',{...actingOpen,...change},false);
input('acting.context.close',{context:receipt},true);
input('acting.context.close',{context:'1-1-1-1-1'},false);
const actingOpened={context:receipt,subject:'alice',expiresAt:'2026-10-16T00:05:00Z'};
result('acting.context.open',actingOpened,true);
result('acting.context.open',{...actingOpened,context:'bad'},false);
result('acting.context.open',{...actingOpened,subject:'@service/bad'},false);
result('acting.context.open',{...actingOpened,expiresAt:'bad'},false);
const appReceipt={project:'fixture',requestId:receipt,tokenId:receipt,principal:'@service/'+receipt,expiresAt:'2026-11-01T00:00:00Z'};
const appIssue={project:'fixture',requestId:receipt,expiresInDays:30,rotate:false};
input('application.token.issue',appIssue,true);
for(const change of [{expiresInDays:0},{expiresInDays:366},{rotate:null},{requestId:'bad'},{project:'personal:operator'},{extra:1}]) input('application.token.issue',{...appIssue,...change},false);
input('application.token.receipt',{project:'fixture',requestId:receipt},true);
result('application.identity.status',{project:'fixture',identity:null},true);
result('application.token.receipt',appReceipt,true);
result('application.token.receipt',{...appReceipt,principal:'forged'},false);
result('application.token.issue',{receipt:appReceipt,credential:'pss_'+'1'.repeat(48)},true);
result('application.token.issue',{receipt:appReceipt,credential:'invalid'},false);
input('schedule.list',{project:'fixture'},true);
input('schedule.files',{project:null},false);
input('document.detail',{document:receipt,scope:{kind:'project',project:'fixture',includeGlobal:false}},true);
input('document.detail',{document:receipt,scope:{kind:'project',project:'fixture',extra:true}},false);
// Explicit project or existing conversation: omission/null must never choose Personal implicitly.
for (const operation of ['conversation.open','conversation.list','conversation.latest','orchestration.start','agent.run']) {
  const base = operation === 'agent.run' ? {agent:'fixture',task:'work'} :
    operation === 'conversation.latest' ? {agent:'fixture'} :
    operation === 'orchestration.start' ? {agent:'fixture',definition:'workflow',request:'work',requestId:receipt} : {};
  input(operation,base,false);
  input(operation,{...base,project:null},['conversation.list','conversation.latest'].includes(operation));
  input(operation,{...base,project:'fixture'},true);
  input(operation,{...base,project:'personal:66697874757265'},true);
}
input('agent.run',{agent:'fixture',task:'work',conversation:'cnv_fixture'},true);
input('project.list', {}, true);
input('project.list', { future: 1 }, false);
for (const job of ['job_fixture', '', '  '])
  input('job.status', { job }, job === 'job_fixture');
for (const limit of [1, 0, -1, 1.5, 2147483648])
  input('admin.audit', { limit }, limit === 1);
input('approval.list', { mine: true }, true);
input('approval.list', {}, false);
input('approval.list', { mine: true, project: 'fixture' }, false);
input('admin.account.update', { handle: 'fixture' }, false);
input('admin.account.update', { handle: 'fixture', enabled: false }, true);
input(
  'agent.run',
  { agent: 'fixture', task: 'free text', conversation: 'c', project: 'p' },
  false,
);
input(
  'agent.run',
  {
    agent: 'fixture',
    task: 'free text',
    newConversation: true,
    conversation: 'c',
  },
  false,
);
input(
  'agent.run',
  { agent: 'fixture', task: 'free text', maxModelCalls: 1 },
  false,
);
input('schedule.pause', { schedule: 'fixture' }, false);
input('schedule.pause', { schedule: 'fixture', paused: false }, true);
input('information.status', { scope: { kind: 'personal' } }, false);
input(
  'information.status',
  { scope: { kind: 'personal' }, revision: receipt, acquisition: receipt },
  false,
);
input(
  'information.status',
  { scope: { kind: 'personal' }, revision: receipt },
  true,
);
// Relay causation is an optional response field for older peers, with strict shared invariants.
const relayPage = {
  scope: { project: 'fixture', system: false },
  topic: { name: 'job.ended', kind: 'LIFECYCLE', retentionSeconds: '345600', maxRecords: null, through: '1', expiredThrough: '0' },
  after: '0', next: '1', gapThrough: null, subscribers: [], branches: [],
  events: [{ position: '1', eventId: 'source:event', publisher: 'system.source', occurredAt: '2026-10-06T00:00:00Z', publishedAt: '2026-10-06T00:00:00Z', correlationId: null, causationId: null,
    payload: { kind: 'LIFECYCLE', text: null, schedule: null, emits: null, fireAt: null, lifecycle: { source: 'job.ended', subject: 'job_fixture', state: 'ANSWERED', context: null, related: null } } }],
};
result('relay.log', relayPage, true);
for (const [causation, valid] of [
  [{ rootId: 'root', parentId: null, depth: 0 }, true],
  [{ rootId: 'root', parentId: 'parent', depth: 8 }, true],
  [{ rootId: 'legacy', parentId: null, depth: -1 }, true],
  [{ rootId: 'root', parentId: null, depth: 1 }, false],
  [{ rootId: 'root', parentId: 'parent', depth: 0 }, false],
  [{ rootId: 'root', parentId: 'parent', depth: 33 }, false],
  [{ rootId: 'root', parentId: null, depth: -2 }, false],
  [{ rootId: 'root', parentId: 'parent', depth: 1.5 }, false],
]) result('relay.log', { ...relayPage, events: [{ ...relayPage.events[0], causation }] }, valid);
// Generic topic ports preserve their project, consumer and explicit batch authority.
const publish = {requestId:receipt,project:'fixture',topic:'checks.requests',text:'opaque application JSON',occurredAt:'2026-10-07T00:00:00Z'};
input('relay.publish',publish,true);
for(const change of [{text:' '},{text:'\0bad'},{requestId:'bad'},{topic:'System Topic'},{parentTopic:'checks.source'},{occurredAt:'invalid'},{publisher:'forged'},{requestId:receipt.toUpperCase().replace('11111111','ABCDEFAB')}]) input('relay.publish',{...publish,...change},false);
input('relay.publish',{...publish,parentTopic:'checks.source',parentEventId:'event'},true);
const consume = {project:'fixture',topic:'checks.requests',group:'detectors',consumerId:receipt,start:'OLDEST_RETAINED'};
input('relay.consume',consume,true);
for(const change of [{group:' '},{consumerId:'bad'},{limit:0},{limit:101},{waitMs:30001},{waitMs:1.5},{start:'EARLIEST'}]) input('relay.consume',{...consume,...change},false);
const batch={project:'fixture',topic:'checks.requests',group:'detectors',consumerId:receipt,status:'DATA',batchId:receipt,fence:'1',through:'1',expiresAt:'2026-10-07T00:00:30Z',expiredThrough:null,events:relayPage.events};
result('relay.consume',batch,true);
for(const change of [{events:[]},{batchId:null},{fence:'0'},{fence:'9223372036854775808'},{status:'EMPTY'},{expiredThrough:'1'},{through:'-1'}]) result('relay.consume',{...batch,...change},false);
const empty={...batch,status:'EMPTY',batchId:null,fence:null,expiresAt:null,events:[]};
result('relay.consume',empty,true);
result('relay.consume',{...empty,status:'BUSY'},true);
result('relay.consume',{...batch,status:'GAP',events:[],expiredThrough:'1'},true);
result('relay.consume',{...batch,status:'GAP',events:[]},false);
const ack={project:'fixture',topic:'checks.requests',group:'detectors',consumerId:receipt,batchId:receipt,fence:'1'};
input('relay.ack',ack,true);
for(const change of [{fence:'0'},{fence:'01'},{batchId:'bad'},{expiredThrough:'9223372036854775808'}]) input('relay.ack',{...ack,...change},false);
result('relay.publish',{requestId:receipt,project:'fixture',topic:'checks.requests',position:'1',publishedAt:publish.occurredAt},true);
result('relay.ack',{project:'fixture',topic:'checks.requests',group:'detectors',batchId:receipt,through:'1',gap:false},true);
// Topic capacity is independent of subscribers/groups, across plain and hosted ports.
for (const [length, valid] of [[1024,true],[1025,false]]) {
  const topic = 'a'.repeat(length);
  input('relay.publish',{...publish,topic},valid);
  input('relay.publish',{...publish,parentTopic:topic,parentEventId:'parent'},valid);
  input('relay.consume',{...consume,topic},valid);
  input('relay.ack',{...ack,topic},valid);
  input('relay.log',{project:'fixture',topic},valid);
  input('relay.operate',{requestId:receipt,project:'fixture',topic,topicGeneration:receipt,action:'REMOVE_TOPIC',reason:'fixture'},valid);
  result('relay.log',{...relayPage,topic:{...relayPage.topic,name:topic}},valid);
  result('relay.log',{...relayPage,events:[{...relayPage.events[0],payload:{...relayPage.events[0].payload,lifecycle:{...relayPage.events[0].payload.lifecycle,source:topic}}}]},valid);
  result('relay.consume',{...batch,topic},valid);
  input('tool.scope.worker.publish',{...hostWorker,publication:{...hostPublication,topic}},valid);
  input('tool.scope.worker.consume',{...hostWorker,consumption:{...hostConsumption,topic}},valid);
  input('tool.scope.worker.ack',{...hostWorker,acknowledgement:{...hostAck,topic}},valid);
}
const message = { parts: [{ text: 'hello' }] };
input('outgoing.send', { requestId: receipt, peer: 'fixture', message }, true);
for (const requestId of ['bad', ''])
  input('outgoing.send', { requestId, peer: 'fixture', message }, false);
for (const parts of [
  [],
  [{ text: '\0bad' }],
  [{ text: 3 }],
  [{ text: 'hi', raw: 'AAAA' }],
  [{ raw: 'AB==' }],
  [{ url: 'https://user:secret@example.invalid/a' }],
  [{ text: 'ok', filename: '../x' }],
])
  input(
    'outgoing.send',
    { requestId: receipt, peer: 'fixture', message: { parts } },
    false,
  );
input('outgoing.claim', { peers: ['fixture'] }, true);
input('outgoing.claim', { peers: [] }, false);
input('outgoing.claim', { peers: ['fixture', 'fixture'] }, false);
input(
  'outgoing.report',
  { id: receipt, claim: 'fixture', revision: -1, state: 'WORKING' },
  false,
);
result('project.list', [], true);
const project = {
  name: 'fixture',
  workspace: 'fixture',
  machine: null,
  members: [],
  lent: [],
  exclusions: [],
};
result('project.list', [{ ...project, future: 1 }], true);
result('project.list', [{ ...project, members: [1] }], false);
result('project.list', [{ ...project, machine: 42 }], false);
result('project.list', [{ name: 'partial' }], false);
result('conversation.follow', null, true);
const informationSearch = JSON.parse(readFileSync(
  new URL('../test-support/contracts/ws-information-fixtures.json', import.meta.url),
  'utf8',
))['information.search'].payload;
result('information.search', informationSearch, true);
result('information.search', [{ ...informationSearch[0], distance: 'near' }], false);
result('information.search', [{ ...informationSearch[0], chunk: { ...informationSearch[0].chunk, placement: { sectionId: 'partial' } } }], false);
result('information.search', { query: 'question', document: null, limit: 10, hits: [] }, false);
result('outgoing.claim', { work: null, action: null }, true);
result('outgoing.claim', { work: null, action: 'send' }, false);
push({ kind: 'inbox.changed', unread: 1, future: 1 }, true);
push({ kind: 'information.invalidated' }, true);
push({ kind: 'information.invalidated', future: 'ignored' }, true);
for (const unread of [-1, 1.5, '1'])
  push({ kind: 'inbox.changed', unread }, false);
push(
  {
    kind: 'orchestration.resumed',
    orchestration: 'fixture',
    requestId: receipt,
  },
  true,
);
push(
  { kind: 'orchestration.resumed', orchestration: 'fixture', requestId: 'bad' },
  false,
);
push({ job: 'fixture', kind: 'started' }, false);
push(
  {
    job: 'fixture',
    kind: 'started',
    agent: 'fixture',
    steps: 0,
    modelCalls: 0,
  },
  true,
);
push({ job: 'fixture', part: 'ANSWER', text: 'text' }, true);
push({ job: 'fixture', part: 'ANSWER', text: '\0bad' }, false);
push(
  {
    id: null,
    type: 'usage.closed',
    protocol_version: 'plowshare-v1',
    payload: { subscription: receipt, code: 'BAD_REQUEST' },
  },
  true,
);
// Portable host references must have identical strict semantics in every SDK.
const root = { store: 'applications', path: 'chatbot' };
const area = { store: 'outputs', path: 'chatbot/reports' };
const deployment = { project: 'app', requestId: receipt, expectedRevision: null, destination: root, writableAreas: [], files: [{path:'plowshare.json',text:'{"version":1}'}] };
input('application.deploy', deployment, true);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'agents/worker.md',text:'source'}]},true);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'.plowshare/agents/worker.md',text:'source'}]},false);
input('application.deploy', {...deployment,requestId:receipt+'\n'},false);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'notes.md\n',text:'x'}]},false);
input('application.deploy', {...deployment, expectedRevision: receipt}, true);
input('application.deploy', {...deployment,destination:{store:'applications',path:''}},false);
input('application.deploy', {...deployment, files: []}, false);
input('application.deploy', {...deployment, files: [{path:'../escape',text:'x'}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'Plowshare.json',text:'x'}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'a',text:'😀'.repeat(16384)}, {path:'b',text:'😀'.repeat(16384)}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'a',text:'x'}, {path:'a/b',text:'x'}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'.env',text:'x'}]}, false);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'a/'.repeat(17)+'file',text:'x'}]},false);
input('application.activate', {project:'app',requestId:receipt,expectedRevision:receipt,revision:receipt}, true);
input('application.activate', {project:'app',requestId:receipt,expectedRevision:null,revision:receipt}, false);
result('application.deploy', {project:'app',requestId:receipt,release:{revision:receipt,digest:'a'.repeat(64),fileCount:1}}, true);
result('application.deployment.status', {project:'app',activeRevision:null,releases:[]}, true);
result('application.deploy',{project:'app',requestId:receipt,release:{revision:receipt,digest:'a'.repeat(64)+'\n',fileCount:1}},false);
result('application.deployment.status', {project:'app',activeRevision:receipt,releases:[{revision:receipt,digest:'a'.repeat(64),fileCount:0}]}, false);
input('application.create', { name: 'chatbot', applicationRoot: root, writableAreas: [area] }, true);
input('application.storage.set', { project: 'chatbot', applicationRoot: root, writableAreas: [] }, true);
input('application.create', { name: 'chatbot', applicationRoot: root }, false);
input('application.create', { name: 'chatbot', applicationRoot: root, writableAreas: [area, area] }, false);
input('project.create', { name: 'chatbot', applicationRoot: root, writableAreas: [] }, false);
input('application.create', { name: 'chatbot', workspace: '/legacy', applicationRoot: root, writableAreas: [] }, false);
const file = { project: 'chatbot', path: 'report.md', text: '', revision: 'a'.repeat(64), writable: true, location: area };
result('application.file.read', file, true);
result('application.file.read', { ...file, location: { ...area, future: true } }, true);
const placedProject = { name: 'chatbot', workspace: '/fixture', machine: null, members: [], lent: [], exclusions: [], applicationRoot: root, writableAreas: [area] };
result('application.create', placedProject, true);
result('application.storage.set', { ...placedProject, writableAreas: Array(101).fill(area) }, false);
for (const invalid of [
  { store: 'Upper', path: '' }, { store: 'outputs', path: '../secret' },
  { store: 'outputs', path: '/absolute' }, { store: 'outputs', path: 'a//b' },
  { store: 'outputs', path: 'a/./b' }, { store: 'outputs', path: 'a\\b' },
  { store: 'outputs', path: 'a:stream' }, { store: 'outputs', path: '.GiT/config' },
  { store: 'outputs', path: 'a\u0085' }, { store: 'outputs', path: '' , extra: true },
]) {
  input('application.file.read', { project: 'chatbot', path: 'report.md', location: invalid }, false);
  // Unknown reply fields are projected away for forward compatibility.
  if (!('extra' in invalid)) result('application.file.read', { ...file, location: invalid }, false);
}
input('application.files', { project: 'chatbot', location: { store: 'outputs', path: '' } }, true);

const namedSelection = {name:'privacy', revision:'a'.repeat(64), description:'Review privacy', members:['researcher'], budget:20};
const namedType = {name:'privacy', members:['researcher'], budget:20, refused:{}, origin:'swarm/privacy.md', selection:namedSelection};
result('swarm.types', {project:'fixture', types:[namedType]}, true);
result('swarm.types', {project:'fixture', types:[{...namedType,budget:null,selection:{...namedSelection,budget:null}}]}, true);
result('swarm.types', {project:'fixture', types:[{...namedType,budget:null}]}, false);
result('swarm.types', {project:'fixture', types:[]}, true);
for (const change of [{name:'../privacy'}, {revision:'bad'}, {budget:1}, {budget:2.5}, {description:'x'.repeat(4097)}, {members:['researcher','researcher']}, {members:[' ']}])
  result('swarm.types', {project:'fixture', types:[{...namedType, selection:{...namedSelection,...change}}]}, false);
result('swarm.types', {project:'fixture', types:[{...namedType, members:['critic']}]}, false);
const namedOpening = {project:'fixture',title:'Topic',label:'Review',body:'Evidence',requestId:receipt,swarm:'privacy'};
input('board.open', namedOpening, true);
for (const swarm of ['../privacy', 'Privacy', '', 'p'.repeat(65), null]) input('board.open', {...namedOpening,swarm}, false);
input('swarm.types', {project:'fixture'}, true);
input('swarm.types', {}, false);
input('filestore.list', {}, true);
input('filestore.list', {account:'other'}, false);
result('filestore.list', {stores:[]}, true);
result('filestore.list', {stores:[{alias:'applications',role:'MANAGER'},{alias:'reports',role:'CONTRIBUTOR'},{alias:'archive',role:'VIEWER'}]}, true);
result('filestore.list', {stores:[{alias:'../private',role:'MANAGER'}]}, false);
result('filestore.list', {stores:[{alias:'applications',role:'OWNER'}]}, false);
result('filestore.list', {stores:Array(101).fill({alias:'applications',role:'MANAGER'})}, false);
// Scope discovery is independent of grant policy; every SDK validates the same DTO boundary.
const scopeConnection = { project: 'fixture', scope: 'linear', provider: 'linear', prefix: 'linear_',
  grants: ['*'], agents: ['ticketer'], leaseSeconds: 30 };
input('tool.scope.connect', scopeConnection, true);
input('tool.scope.connect', { ...scopeConnection, grants: ['linear_read'] }, true);
for (const change of [
  { grants: ['*', 'linear_read'] }, { grants: [] }, { grants: ['x', 'x'] },
  { grants: ['linear_read\n'] }, { agents: [] }, { agents: ['ticketer', 'ticketer'] },
  { provider: 'Bad.Provider' }, { prefix: 'linear' }, { leaseSeconds: 0 },
  { leaseSeconds: 301 }, { leaseSeconds: 1.5 }, { account: 'intruder' },
]) input('tool.scope.connect', { ...scopeConnection, ...change }, false);
for(const [length,valid] of [[256,true],[257,false]]) {
  const grant = 'a'.repeat(length);
  input('tool.scope.connect',{...scopeConnection,grants:[grant]},valid);
  input('tool.scope.host.save',{...hostSave,definition:{...hostDefinition,grants:['network_'+'a'.repeat(length-8)]}},valid);
}
input('tool.scope.disconnect', { project: 'fixture', scope: 'linear' }, true);
result('tool.scope.connect', { ...scopeConnection, sourceProvider: 'linear', provider: 'p-abc', account: 'alice' }, true);
result('tool.scope.list', { connections: [] }, true);
result('tool.scope.disconnect', { project: 'fixture', scope: 'linear', disconnected: true }, true);

const procSelection={visibility:'mine',project:null,conversation:null,root:null,causation:null,active:null,after:null,limit:50};
const procPage={snapshotAt:'2026-10-19T01:00:00Z',selection:procSelection,conversations:[],total:0,cursor:null,coverage:[]};
for(const operation of ['proc.list','proc.calls','proc.queues','proc.tools']) {
  input(operation,{},true);
  input(operation,{visibility:'server',limit:100,active:true},true);
  input(operation,{conversation:'cnv_fixture',after:'cursor:fixture'},true);
  input(operation,{after:'x'.repeat(257)},false);
  for(const change of [{limit:0},{limit:101},{limit:1.5},{active:'true'},{account:'other'},{conversation:'bad\u0000id'},{visibility:'admin'}])input(operation,change,false);
}
input('proc.inspect',{},false);
input('proc.inspect',{root:'cnv_fixture'},true);
input('proc.inspect',{conversation:'cnv_fixture',after:'cursor:fixture'},true);
input('proc.inspect',{causation:'chain-fixture',visibility:'server'},true);
result('proc.list',procPage,true);
result('proc.list',{...procPage,total:-1},false);
result('proc.list',{...procPage,snapshotAt:'yesterday'},false);
result('proc.tools',{snapshotAt:procPage.snapshotAt,selection:procSelection,tools:[],cursor:null,coverage:[]},true);
result('proc.calls',{snapshotAt:procPage.snapshotAt,selection:procSelection,calls:[],cursor:null,coverage:[]},true);
result('proc.queues',{snapshotAt:procPage.snapshotAt,selection:procSelection,queues:[],summaries:[],pools:[],cursor:null,coverage:[]},true);

// Schedule inspection uses exclusive stable names, independently of numeric event cursors.
for (const operation of ['schedule.list', 'schedule.files']) {
  for (const value of [{}, {limit:1}, {after:'scheduled-1-daily',limit:100},
    {project:'fixture',after:'nightly',limit:51}]) input(operation,value,true);
  for (const value of [{after:null}, {after:1}, {after:''}, {after:' daily'},
    {after:'bad\u0085cursor'}, {after:'x'.repeat(513)}, {limit:0}, {limit:101},
    {limit:1.5}, {limit:'50'}, {offset:50}]) input(operation,value,false);
}

for (const test of cases) {
  let accepted = true;
  try {
    if (test.boundary === 'input') decodePayload(test.operation, test.value);
    else if (test.boundary === 'result')
      decodeReply(test.operation, test.value);
    else decodeServerPush(test.value);
  } catch {
    accepted = false;
  }
  if (accepted !== test.valid)
    throw new Error(
      'Fixture differs from canonical contract: ' + JSON.stringify(test),
    );
}
const path = new URL(
    '../test-support/contracts/sdk-dto-conformance.json',
    import.meta.url,
  ),
  content = JSON.stringify(cases, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (readFileSync(path, 'utf8') !== content)
    throw new Error('Stale SDK DTO fixtures');
} else writeFileSync(path, content);
console.log('Canonical SDK DTO cases verified:', cases.length);

// Coordinate fixtures supplement schema cases with structurally valid foreign replies.
const correlations=[];
for(const operation of [...new Set(cases.filter(c=>c.operation?.startsWith('tool.scope.host.')||c.operation?.startsWith('tool.scope.worker.')).map(c=>c.operation))]) {
 const reply=cases.find(c=>c.operation===operation&&c.boundary==='result'&&c.valid)?.value;
 const asked=cases.find(c=>c.operation===operation&&c.boundary==='input'&&c.valid&&(operation!=='tool.scope.worker.credential'||c.value.purpose===reply.purpose))?.value;
 if(reply===undefined||asked===undefined)throw new Error('Missing hosted correlation fixture');
 const changed=structuredClone(asked);
 const nested=operation.endsWith('.publish')?'publication':operation.endsWith('.consume')?'consumption':operation.endsWith('.ack')?'acknowledgement':undefined;
 if(nested)changed[nested].project='foreign';
 else if('requestId' in changed)changed.requestId='99999999-9999-9999-9999-999999999999';
 else if('binding' in changed)changed.binding='99999999-9999-9999-9999-999999999999';
 else changed.project='foreign';
 for(const [request,valid] of [[asked,true],[changed,false]]) {
  decodePayload(operation,request);decodeReply(operation,reply);
  if(hostedReplyMatches(operation,request,reply)!==valid)throw new Error('Invalid hosted correlation fixture');
  correlations.push({operation,request,reply,valid});
 }
}
const correlationPath=new URL('../test-support/contracts/hosted-scope-correlations.json',import.meta.url),correlationContent=JSON.stringify(correlations,null,2)+'\n';
if(process.argv.includes('--check')){if(readFileSync(correlationPath,'utf8')!==correlationContent)throw new Error('Stale hosted correlation fixtures');}else writeFileSync(correlationPath,correlationContent);
