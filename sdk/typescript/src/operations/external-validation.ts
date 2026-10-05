import type { Request, Operation } from './direct.ts';
import type { Replies } from './replies.ts';
import type {
  ExternalMessage,
  ExternalMetadata,
  ExternalResult,
  ExternalTask,
  IntegrationParameter,
  IntegrationRequest,
  AgentCard,
  IncomingSource,
} from './external.ts';
import type { OutgoingWork } from './outgoing.ts';
function require(valid: boolean, field: string): asserts valid {
  if (!valid) throw new Error(`Invalid ${field} contract.`);
}
function identity(
  value: string | null | undefined,
  field: string,
  max = 1024,
): void {
  if (value == null) return;
  require(!!value.trim() &&
    value === value.trim() &&
    value.length <= max &&
    !Array.from(value).some((c) => {
      const n = c.charCodeAt(0);
      return n < 32 || (n >= 127 && n <= 159) || n === 0x2028 || n === 0x2029;
    }), field);
}
function text(
  value: string | null | undefined,
  field: string,
  max: number,
): void {
  if (value != null)
    require(value.length <= max && !value.includes('\0'), field);
}
function unique(values: readonly string[], field: string, max: number): void {
  require(values.length <= max &&
    new Set(values).size === values.length, field);
  for (const v of values) identity(v, field);
}
function absolute(value: string, field: string): void {
  identity(value, field, 8192);
  require(/^[a-z][a-z0-9+.-]*:[^\s]+$/i.test(value), field);
}
/** Neutral code has no platform URL global. This allowlist accepts HTTP(S) host
 * references, excluding embedded credentials, fragments and control characters.
 * It conveys no permission to connect; configured adapters own external I/O. */
function web(
  value: string | null | undefined,
  field: string,
  tls = false,
): void {
  if (value == null) return;
  identity(value, field, 8192);
  const matched =
    /^https?:\/\/(\[[a-f0-9:.]+\]|[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?(?:[/?][^\s#]*)?$/i.exec(
      value,
    );
  require(!!matched &&
    (!tls || value.startsWith('https://')) &&
    (!matched[2] || Number(matched[2]) <= 65535), field);
}
const command =
  /^\/(skill|orchestration):[a-zA-Z0-9_.-]+(?: --mode=(INHERITED|SUMMARISED|NEW|DIRECT))?$/;
function metadata(value: ExternalMetadata | null | undefined): void {
  if (!value) return;
  require(value.plowshareCommand == null ||
    command.test(value.plowshareCommand), 'external command');
  require(value.plowshareEnding == null ||
    [
      '',
      'ANSWERED',
      'TURN_CAP',
      'CALL_BUDGET',
      'CANCELLED',
      'STUCK',
      'UNAVAILABLE',
      'SUB_AGENT_FAILED',
      'SESSION_GONE',
      'AWAITING',
    ].includes(value.plowshareEnding), 'external ending');
}
function parameters(
  values: Readonly<Record<string, IntegrationParameter>>,
): void {
  require(Object.keys(values).length <= 256, 'integration parameters');
  for (const [key, value] of Object.entries(values)) {
    identity(key, 'parameter name', 256);
    require(/^[A-Za-z_][A-Za-z0-9_]*$/.test(key), 'parameter name');
    if (typeof value === 'string') text(value, 'parameter text', 4096);
    if (typeof value === 'number') {
      require(Number.isFinite(value) &&
        (!Number.isInteger(value) ||
          Number.isSafeInteger(value)), 'parameter number precision');
      const literal = String(value),
        exponent = literal.split(/[eE]/)[1];
      require(exponent === undefined
        ? (literal.split('.')[1]?.length ?? 0) <= 18
        : Math.abs(Number(exponent)) <= 18, 'parameter number scale');
    }
  }
}
function integration(value: IntegrationRequest): void {
  identity(value.binding, 'binding', 256);
  if (value.operation === 'states.read') {
    require(value.arguments.entities.length > 0, 'selected entities');
    unique(value.arguments.entities, 'selected entities', 256);
  } else {
    identity(value.arguments.action, 'action', 256);
    parameters(value.arguments.parameters);
  }
}
export function validateExternalMessage(value: ExternalMessage): void {
  identity(value.messageId, 'message id');
  identity(value.contextId, 'message context');
  identity(value.taskId, 'message task');
  metadata(value.metadata);
  require(value.parts.length > 0 && value.parts.length <= 256, 'message parts');
  if (value.extensions) {
    unique(value.extensions, 'extensions', 32);
    for (const v of value.extensions) absolute(v, 'extension');
  }
  for (const part of value.parts) {
    metadata(part.metadata);
    identity(part.mediaType, 'media type', 256);
    identity(part.filename, 'filename');
    if (part.mediaType)
      require(/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(
        part.mediaType,
      ), 'media type');
    if (part.filename)
      require(!/[\\/]/.test(part.filename) &&
        part.filename !== '.' &&
        part.filename !== '..', 'filename');
    if (part.text !== undefined) text(part.text, 'part text', 256 * 1024);
    else if (part.raw !== undefined)
      require(part.raw.length <= 256 * 1024 &&
        /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/][AQgw]==|[A-Za-z0-9+/]{2}[AEIMQUYcgkosw048]=)?$/.test(
          part.raw,
        ), 'canonical base64 part');
    else if (part.url !== undefined) web(part.url, 'part URL');
    else integration(part.data);
  }
}
function response(value: ExternalMessage): void {
  validateExternalMessage(value);
  require(!!value.messageId &&
    value.role === 'ROLE_AGENT', 'remote response identity');
}
function task(value: ExternalTask): void {
  identity(value.id, 'task id');
  identity(value.contextId, 'task context');
  const matching = (message: ExternalMessage): void => {
    require((message.taskId == null || message.taskId === value.id) &&
      (message.contextId == null ||
        message.contextId === value.contextId), 'task message identity');
  };
  if (value.status.timestamp != null)
    require(Number.isFinite(
      Date.parse(value.status.timestamp),
    ), 'task timestamp');
  if (value.status.message) {
    response(value.status.message);
    matching(value.status.message);
  }
  if (value.history) {
    require(value.history.length <= 256, 'task history');
    for (const m of value.history) {
      validateExternalMessage(m);
      require(!!m.messageId && !!m.role, 'history identity');
      matching(m);
    }
  }
  if (value.artifacts) {
    unique(
      value.artifacts.map((a) => a.artifactId),
      'artifacts',
      256,
    );
    for (const a of value.artifacts) {
      text(a.name, 'artifact name', 1024);
      text(a.description, 'artifact description', 32768);
      validateExternalMessage({
        parts: a.parts,
        ...(a.extensions == null ? {} : { extensions: a.extensions }),
        ...(a.metadata == null ? {} : { metadata: a.metadata }),
      });
    }
  }
  metadata(value.metadata);
}
function result(
  value: ExternalResult | null | undefined,
  remoteTask?: string | null,
  remoteContext?: string | null,
): void {
  if (value == null) return;
  if (value.task) {
    task(value.task);
    require(value.task.id === remoteTask &&
      value.task.contextId === remoteContext, 'remote task observation');
  } else if (value.message) {
    response(value.message);
    require((value.message.taskId == null ||
      value.message.taskId === remoteTask) &&
      (value.message.contextId == null ||
        value.message.contextId ===
          remoteContext), 'remote message observation');
  } else if (value.states) {
    require(Object.keys(value.states).length <= 256, 'selected states');
    for (const [alias, r] of Object.entries(value.states)) {
      require(alias === r.alias, 'reading alias');
      identity(r.alias, 'reading alias', 256);
      text(r.state, 'reading state', 4096);
      parameters(r.attributes);
      text(r.unit, 'reading unit', 256);
      identity(r.epoch, 'reading epoch');
      for (const time of [r.last_changed, r.last_updated, r.observed_at])
        if (time)
          require(Number.isFinite(Date.parse(time)), 'reading timestamp');
    }
  } else {
    text(value.diagnostic, 'integration diagnostic', 32768);
    require(value.acknowledged !== false ||
      value.context == null, 'action context');
  }
}
function card(value: AgentCard): void {
  identity(value.name, 'card name');
  text(value.description, 'card description', 32768);
  identity(value.version, 'card version', 128);
  require(value.skills.length <= 256, 'card skills');
  for (const skill of value.skills) {
    identity(skill.id, 'skill id');
    identity(skill.name, 'skill name');
    text(skill.description, 'skill description', 32768);
    require(skill.tags.length <= 128, 'skill tags');
  }
  for (const endpoint of value.supportedInterfaces ?? []) {
    web(endpoint.url, 'interface URL');
    identity(endpoint.protocolBinding, 'protocol binding', 128);
    identity(endpoint.protocolVersion, 'protocol version', 128);
    identity(endpoint.tenant, 'tenant');
  }
  web(value.provider?.url, 'provider URL');
  web(value.documentationUrl, 'documentation URL');
  web(value.iconUrl, 'icon URL');
  for (const scheme of Object.values(value.securitySchemes ?? {})) {
    if (scheme.openIdConnectSecurityScheme)
      web(
        scheme.openIdConnectSecurityScheme.openIdConnectUrl,
        'OpenID URL',
        true,
      );
    if (scheme.oauth2SecurityScheme) {
      web(
        scheme.oauth2SecurityScheme.oauth2MetadataUrl,
        'OAuth metadata URL',
        true,
      );
      const flows = scheme.oauth2SecurityScheme.flows;
      for (const flow of [
        flows.authorizationCode,
        flows.clientCredentials,
        flows.implicit,
        flows.password,
        flows.deviceCode,
      ])
        if (flow) {
          web(flow.refreshUrl, 'OAuth refresh URL', true);
          if (
            'authorizationUrl' in flow &&
            typeof flow.authorizationUrl === 'string'
          )
            web(flow.authorizationUrl, 'OAuth authorization URL', true);
          if ('tokenUrl' in flow && typeof flow.tokenUrl === 'string')
            web(flow.tokenUrl, 'OAuth token URL', true);
          if (
            'deviceAuthorizationUrl' in flow &&
            typeof flow.deviceAuthorizationUrl === 'string'
          )
            web(flow.deviceAuthorizationUrl, 'OAuth device URL', true);
        }
    }
  }
  for (const required of value.securityRequirements ?? [])
    for (const key of Object.keys(required.schemes))
      require(Object.hasOwn(
        value.securitySchemes ?? {},
        key,
      ), 'declared security scheme');
}
function source(value: IncomingSource): void {
  identity(value.messageId, 'source message', 256);
  require(value.parts.length > 0 && value.parts.length <= 256, 'source parts');
  let bytes = 0;
  for (const part of value.parts) {
    text(part.text, 'source text', 65536);
    for (const c of Array.from(part.text)) {
      const n = c.codePointAt(0) ?? 0;
      bytes += n > 65535 ? 4 : n > 2047 ? 3 : n > 127 ? 2 : 1;
    }
  }
  require(bytes <= 65536, 'source text bytes');
  if (value.metadata?.plowshareCommand)
    require(command.test(value.metadata.plowshareCommand), 'source command');
  if (value.referenceTaskIds)
    unique(value.referenceTaskIds, 'referenced tasks', 256);
  if (value.extensions) unique(value.extensions, 'source extensions', 256);
}
/** Called only after the generated decoder has copied every field into its DTO. */
export function validateExternalRequest(request: Request): void {
  switch (request.type) {
    case 'outgoing.send':
      validateExternalMessage(request.payload.message);
      break;
    case 'outgoing.advertise':
      unique(request.payload.peers, 'advertised peers', 32);
      require(request.payload.peers.length > 0, 'advertised peers');
      for (const [peer, value] of Object.entries(
        request.payload.agentCards ?? {},
      )) {
        require(request.payload.peers.includes(
          peer,
        ), 'advertised card identity');
        card(value);
      }
      break;
    case 'outgoing.claim':
      unique(request.payload.peers, 'claimed peers', 32);
      require(request.payload.peers.length > 0, 'claimed peers');
      break;
    case 'outgoing.report': {
      const p = request.payload;
      require(Number.isSafeInteger(p.revision) &&
        p.revision >= 0, 'report revision');
      require([
        'WORKING',
        'INPUT_REQUIRED',
        'AUTH_REQUIRED',
        'COMPLETED',
        'FAILED',
        'CANCELED',
        'REJECTED',
        'UNKNOWN',
      ].includes(p.state), 'reported state');
      if (['WORKING', 'INPUT_REQUIRED', 'AUTH_REQUIRED'].includes(p.state))
        require(!!p.remoteTask, 'nonterminal task identity');
      identity(p.remoteTask, 'remote task');
      identity(p.remoteContext, 'remote context');
      text(p.error, 'remote error', 2000);
      result(p.result, p.remoteTask, p.remoteContext);
      if (p.result?.task && p.state !== 'UNKNOWN')
        require(p.state ===
          p.result.task.status.state
            .replace('TASK_STATE_', '')
            .replace('SUBMITTED', 'WORKING'), 'reported task state');
      break;
    }
    case 'incoming.receive': {
      const p = request.payload;
      text(p.body, 'incoming body', 65536);
      if (p.command) require(command.test(p.command), 'incoming command');
      if (p.source) {
        source(p.source);
        require(p.source.taskId == null &&
          p.source.parts.map((part) => part.text).join('\n') === p.body &&
          (p.source.metadata?.plowshareCommand ?? null) ===
            (p.command ?? null) &&
          (p.source.contextId ?? null) ===
            (p.context ?? null), 'incoming source provenance');
      }
      break;
    }
    default:
      break;
  }
}
function work(value: OutgoingWork): void {
  validateExternalMessage(value.message);
  identity(value.remoteTask, 'remote task');
  identity(value.remoteContext, 'remote context');
  text(value.error, 'remote error', 2000);
  require(Number.isSafeInteger(value.revision) &&
    value.revision >= 0, 'work revision');
  result(value.result, value.remoteTask, value.remoteContext);
}
type Response = {
  [K in Operation]: { type: K; payload: Replies[K] };
}[Operation];
export function validateExternalResponse(reply: Response): void {
  switch (reply.type) {
    case 'outgoing.send':
    case 'outgoing.status':
    case 'outgoing.cancel':
    case 'outgoing.report':
      work(reply.payload);
      break;
    case 'outgoing.claim':
      require((reply.payload.work === null) ===
        (reply.payload.action === null), 'claim association');
      if (reply.payload.work) {
        work(reply.payload.work);
        require(reply.payload.action === 'send'
          ? reply.payload.work.state === 'DISPATCHED' &&
              reply.payload.work.remoteTask === null
          : reply.payload.work.remoteTask !== null, 'claim action');
      }
      break;
    case 'outgoing.peers':
      unique(reply.payload.peers, 'peers', 32);
      for (const peer of reply.payload.details ?? []) {
        require(reply.payload.peers.includes(peer.peer), 'listed peer');
        if (peer.agentCard) card(peer.agentCard);
      }
      break;
    case 'incoming.receive':
    case 'incoming.status':
    case 'incoming.cancel':
      if (reply.payload.source) source(reply.payload.source);
      break;
    default:
      break;
  }
}
