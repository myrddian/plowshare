# Troubleshooting and reference

## Diagnose the owning record first

Keep the actual conversation, job, orchestration, acquisition, revision, message
or outgoing-work ID. The UI symptom is only a starting clue. Read the owning
status/record before retrying costly work or deleting state. A timeout can mean
the reply was lost after acceptance; it does not prove nothing happened.

| Symptom | First check | Useful next step |
| --- | --- | --- |
| Cannot connect | Server origin, readiness and account/session status | Complete setup/password change or explicit login; verify WS proxy upgrade |
| Accepted but no answer | Job status and its exact terminal outcome | Follow existing job/result; retain partial output |
| Project refused | Current membership and project identity | Select the intended account/project; do not guess another account's Personal route |
| File unavailable | Rooted channel, containment and granted scopes | Attach the correct root explicitly; inspect the denied path/policy |
| Command denied | Local/server policy, write-area restrictions, hook and approval | Correct the authorized policy; do not try another side to evade it |
| Agent missing | Effective roster and disablement reason | Fix the authoritative definition or unavailable model binding |
| Source saved but blank | Acquisition and revision extract/derive status | Repair the actual failed stage; do not re-fetch merely to read retained text |
| Semantic search incomplete | Projection readiness and configuration compatibility | Explicitly rebuild incompatible projections after diagnosing cost/configuration |
| Research stalled | Orchestration status, pending question, budget and source readiness | Answer the current question or inspect the failed stage and retained responses |
| Message sent with no reply | Instance/message history, wake state, expected-reply setting | Inspect recipient handling outcome and its allowance |
| Swarm member capped | Member outcome and shared reserve | Choose the supported retry/top-up action deliberately |
| External action uncertain | Existing outgoing work and remote task/acknowledgment | Inspect/recover that identity; do not invent a new UUID and resend |
| Server refuses accounting startup | Journal writer ownership or capacity | Stop the intended competing process or repair storage; preserve the journal |
| Manual missing | Installer summary, selected scope and tag filter | Use the installing account or Shared, then inspect returned revision readiness |

## Read operational outcomes accurately

An operation's code and the work's ending answer different questions. Immediate
`OK` is a successful operation response. `ACCEPTED` says work was admitted.
The eventual run may answer, stop incomplete, await input, reach a cap, be refused,
be cancelled, lose a dependency or encounter unavailable inference. Preserve the
actual outcome and any partial result.

Approval, continuation, budget top-up and retry are explicit actions on existing
work. They are not synonyms for starting a new task. Use the current pending
identity and current effective state so an old UI selection cannot answer a newer
request. The server remains authoritative if a client view is stale.

## Offline command discovery

The CLI can list current command families and validate payloads offline:

```sh
bin/plowshare-cli --help
bin/plowshare-cli information list --help
bin/plowshare-cli --validate information list '{"scope":{"kind":"shared"},"filter":{"tags":["plowshare-manual"]},"limit":100}'
```

Use command help for exact required fields and supported aliases. The operation
catalog and generated request/reply schemas are the developer reference. GUI
labels and model-facing tools can present a narrower surface than raw operations.

| Operation family | Examples and purpose |
| --- | --- |
| Account/admin | Setup, session, administrator role and supported account management |
| Project/client/union | Create/list, membership, rooted files, sync status/conflicts |
| Agent/conversation/job | Definition roster, run, transcript/context, follows and terminal results |
| Information/document | Admission, scope/facets, readiness, reads, evidence, reports, retrieval and lifecycle |
| Memory | Recall/read, proposals, review, curation and digest navigation |
| Orchestration/approval | Definitions/start/receipt, stages/records, questions, caps and cancellation |
| Board/swarm/message | Topic/seats, shared deliberation, instances, routes and asynchronous exchanges |
| Schedule/trigger/event/firing/inbox | Timed/event work, occurrence history and durable deliveries |
| Usage | Owned calls/attempts, aggregate reports, token counting and subscriptions |
| Outgoing/incoming | Adapter-facing external-work admission, claims and results |
| Provider/buffer/retention | Explicit operator maintenance and provider registration controls |

## Information reference for manual readers

Catalogue listing is bounded and paged. Use `offset`/`limit` and the selected scope;
filters intersect before counts and ranking. A tag-filtered list is useful even
when model summaries are unavailable. A long manual may have more versions than
one page because exclusion and audience are revision lifecycle choices.

```json
{"scope":{"kind":"shared"},"filter":{"tags":["plowshare-manual"],"search":"WebSocket"},"offset":0,"limit":100}
```

Read a selected revision using the returned ID:

```json
{"scope":{"kind":"shared"},"revision":"<revision-uuid>","offset":0,"limit":8192}
```

For agents, `information_read` uses `operation` plus these read/discovery fields,
but binds account and home from the run rather than accepting model-supplied
ownership. Ask it to find the manual chapter and cite what it read; giving it
the revision ID helps navigation but does not bypass live access checks.

## Transport boundaries

Operational client/server requests use authenticated WebSocket frames. HTTP is
used for authentication/tickets, the readiness probe, supported binary uploads,
Git byte transfer and external protocols/provider boundaries. An operation
without a WS contract does not silently acquire one by changing its URL.

Reverse proxies must preserve the configured origin, authenticated upgrade and
the supported binary/Git routes used by the deployment. Avoid redirects carrying
credentials or an adapter URL that differs from its advertised public URL.

## Reporting a problem

Provide the relevant IDs, selected account/project, operation, observed code/state,
server/client version and a narrow sanitized error excerpt. For model issues,
include the configured model binding and whether the failure was admission,
placement, transport, response parsing or downstream validation. Do not include
API keys, saved tokens, entire private payloads, provider error bodies or database
dumps. A trajectory/diagnostic view can contain more context than is appropriate
for a public issue.

State what was actually tested. Compilation, fixtures, packaging, readiness and a
live end-to-end workflow establish different evidence. A model's confident final
sentence is not a verification result.
