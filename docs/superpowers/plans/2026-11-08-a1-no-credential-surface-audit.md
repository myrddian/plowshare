# A1 — audit of the no-credential surface closures

**Task:** item A1 of
[the contained proxy principal work graph](2026-11-08-contained-proxy-principal-work-graph.md).
Classify every surface ADR 0042 closes on the no-credential fact, and decide
whether a **derived proxy principal** — a real identity row that cannot
authenticate — reopens it.

**Verdict: PROCEED, with three conditions.** The kill condition did not trigger.
No audited surface lands in `OPENED` on the security axis. But the audit found
three things the work graph had wrong or missing, one of which moves the highest
-risk edit in the whole proposal, and one of which decides A3 by evidence
instead of by taste.

---

## Correction to the graph's framing

The graph (and my description of it) said "eleven surfaces". That number comes
from a summary sentence in the spec. ADR 0042's own arithmetic is different and
is the one to use: the inventory holds **thirty-five entries — nineteen reached
by execution and sixteen not**, the sixteen sharing one reason. The phrase
"eleven" in the ADR body belongs to a different count entirely (eleven of
thirteen tools that hand `requireReadTargets` an empty reference list).

So the audited set is **16 unreached rows + the 5 `ProjectAuthorization`
chokepoint exceptions**, the latter because ADR 0042 says of them: *"the handle
and not this chokepoint is what closes them for a child"* — the only place the
record attributes a closure to the handle itself — and because it explicitly
requires that *"the task that makes a contained child execute must still
re-audit all five."*

## A fifth bucket the four did not cover

The graph proposed `SIGN_IN` / `GRANT` / `HANDLE` / `OPENED`. Two rows are
neither closures that survive nor closures that break: they are **capabilities
the proposal breaks** — fail-closed, not holes, but the child stops working
unless they change in the same migration. They are recorded as `CO_CHANGE`.

---

## The decisive fact: sign-in is fenced by kind, not by the sentinel

`JdbcDurableSessions.java:64`:

```sql
SELECT password_hash FROM admins WHERE handle=? AND enabled AND account_kind='USER' FOR UPDATE
```

Session creation **allowlists `account_kind='USER'`**. A new `CONTAINED` kind is
excluded by construction, independently of `enabled` and independently of the
sentinel password hash. `AdminStore:151` fences `server_admin` the same way
(`AND account_kind='USER'`).

This is a stronger and more durable fence than the `BUILTIN` construction I
argued from, and it is why the whole `SIGN_IN` bucket survives. It also means
**A2 is largely pre-answered**: the sign-in path is closed by an allowlist, so
A2 shrinks from "prove four paths fail closed" to "add the regression test that
pins the allowlist".

---

## The classification

### The sixteen rows execution does not reach

| Row | Closure reason | Bucket |
| --- | --- | --- |
| `ProjectStore:1256` `allForSession` | needs a client session to compute its `client:` prefix | `SIGN_IN` |
| `ProjectStore:1317` `ordinary` | create/rename/publish/administer are frames | `SIGN_IN` |
| `JdbcProjectMembers:84` `ordinary`, `:64` `members` | membership frames; read fenced by the write refusal | `SIGN_IN` |
| V216 `authority_membership_changed` | row trigger on `project_members`; a run writes none | `SIGN_IN` (no frame to write one) |
| V216 `authority_map_project` / `authority_scope_shape` | row triggers on `projects` | `SIGN_IN` |
| V216 `authority_live_grants` / `authority_grant_insert` | row triggers on `authority_grants`; *"`contain` copies its container's grant rows rather than issuing any"* | **`CO_CHANGE`** — see F1 |
| `ServiceAccounts:143` `validateScopes` | token issuance is a frame; a contained principal is not a service token | `SIGN_IN` + kind |
| `SourceApplicationDeployments:68` | deployment frame, refused again at the DTO boundary | `SIGN_IN` |
| `ApplicationDeployment:122` | same, DTO constructor | `SIGN_IN` |
| `ApplicationIdentity:105` | identity and credential issuance frame | `SIGN_IN` |
| `HostedToolScopes:313` | durable tool-host frame | `SIGN_IN` |
| `ProjectMessageRouting:65` `identify` | fenced upstream by `require` | `GRANT` |
| ADR 0038 project-scoped Relay subscribers | lease gated on `mayWork` | `GRANT` |
| `UnionFrames:334` `union` | frames, and fenced upstream by `project_type` | `SIGN_IN` + kind |
| `ContainedProjects` | the prefix constant; not a surface | n/a |

**Why `SIGN_IN` dominates.** ADR 0042's shared reason is compound — *"cannot
sign in, holds no handle, and reaches no frame"* — and the operative clause is
the first. A frame is reachable only through an authenticated session, and
session creation allowlists `account_kind='USER'`. The handle clause is
sufficient but not necessary; removing it changes nothing while the kind
allowlist stands.

### The five chokepoint exceptions

ADR 0042 attributes these to the handle, so each was re-read against the code
rather than against that sentence.

| Exception (`ProjectAuthorization`) | Actual upstream closure | Bucket |
| --- | --- | --- |
| `HostedToolScopes.WORKER_OPERATIONS` (`:359`) | worker frames delegate to their typed route service; frame-reachable only | `SIGN_IN` |
| the `proc.*` set (`:364`) | metadata filters whose repository rechecks custody per read | `GRANT` |
| `information.policy.set`/`.get` (`:372`) | resolves a durable reference target, not a project | `SIGN_IN` |
| `ACCOUNT_OWNED` (`:415`), the schedule pair | no schedule id can resolve to a child: `FileScheduleDefinitions:214` registers no source, `JdbcScheduleStore:39` fires nothing | `GRANT` |
| `service && GENERAL_INFORMATION_READ` (`:439`) | a token principal's role comes only from `service_project_role`; V223's arm resolves `authority_project_role` only | `GRANT` + kind |

None is closed by the mere non-existence of a handle. All five survive a proxy
that holds a handle but cannot open a session — **provided** the proxy's kind is
not `SERVICE_TOKEN` and not `SERVICE`, which B1 must pin.

---

## Three findings that change the plan

### F1 — B2 is blocked by the central issuance fence, twice

The work graph said of B2: *"No change to `authority_grants`' shape is expected —
confirm that."* That was wrong, and this is the most consequential finding.
`authority_grant_insert` in
[V216:198](../../../plowshare-server/src/main/resources/db/migration/V216__contained_authority_scopes.sql)
raises unconditionally on **both** of the things B2 needs:

```sql
IF subject_kind NOT IN ('USER','SERVICE') OR destination.kind IN ('RESTRICTED','CONTAINED')
    ... THEN RAISE EXCEPTION 'Recipient or scope cannot receive that role'; END IF;
```

1. A `CONTAINED` account kind is not in the subject allowlist, so the proxy can
   receive **no grant anywhere**.
2. No grant may target a `CONTAINED` scope, for **any** subject. ADR 0042 states
   this as deliberate: *"a grant against a contained scope is excluded from
   issuance and from coverage by design."*

So B2 is not a confirmation step. It is a narrowly-conditioned relaxation of two
conjuncts in the single most security-sensitive trigger in the schema, and it
reverses an explicit design exclusion rather than filling a gap. It should be
re-scoped as the highest-risk item in the graph, with its own adversarial tests,
and A5's decision record must carry the reversal argument for the exclusion as
well as for V218.

The relaxation can be narrow — a `CONTAINED` subject may receive a grant only
when `destination.kind='CONTAINED'`, the destination's container chain reaches a
scope the issuer holds authority on, and `coverage='SELF'` — but "narrow" is a
design claim that needs the tests, not an assertion.

### F2 — the proxy breaks the child's own reads, by construction

`contained_scope_readable`'s self arm
([V223:159](../../../plowshare-server/src/main/resources/db/migration/V223__contained_knowledge_reads.sql))
admits the child only while it has **no identity row**:

```sql
(NOT EXISTS(SELECT 1 FROM admins account WHERE account.handle=$1)
  AND EXISTS(SELECT 1 FROM contained_principals principal
    WHERE principal.scope_id=s.id AND principal.id::text=$1))
```

Give the child an `admins` row and this arm goes false. The same shape appears in
V229's write gates and V232's memory arm, which were built on the same
assumption. So the proxy does not merely add a capability — it **removes the one
slice four delivered**, unless the self arms are retired or rewritten in the same
migration that mints the first proxy.

That is fail-closed, so it is not a security finding. It is a sequencing
constraint: B1/B2/B3 and the removal of the self arms must land together, and
C3's removal of the 24 embeddings stops being optional cleanup and becomes part
of the same change. The `NOT EXISTS admins` condition is currently load-bearing —
it is what distinguishes a bare contained principal from a service principal —
so whatever replaces it must keep service principals out.

### F3 — `account_active` admits by exclusion, which decides A3

`account_active`
([V138:31](../../../plowshare-server/src/main/resources/db/migration/V138__application_owned_identities.sql))
is the system's liveness predicate:

```sql
SELECT EXISTS (SELECT 1 FROM admins a WHERE a.handle=account AND a.enabled AND (
    a.account_kind<>'SERVICE_TOKEN' OR EXISTS (… valid token …)));
```

It allowlists **by exclusion**: any kind that is not `SERVICE_TOKEN` is active
whenever `enabled`. A new `CONTAINED` kind is therefore admitted by default, and
it is referenced by 9 Java files and 13 migrations — `AdminStore`,
`InformationAccess`, `JdbcActingRepository`, `JdbcDurableSessions`,
`JdbcInformationNavigationRepository`, `JdbcProcRepository`,
`JdbcProjectMembers`, `JdbcScopedGrantRepository`,
`JdbcServiceCredentialsRepository`, and V111/V138/V156/V157/V158/V163/V172/
V178/V185/V216/V223/V229/V232.

**This decides A3 on evidence.** The two options were: proxy `enabled=true` (and
rely on the sentinel hash), or `enabled=false` (and teach
`DocumentNavigation.requireActive` the kind). `enabled=true` would silently flip
`account_active` to true at all 22 sites at once — a fail-open change across the
substrate, for a convenience at one call site.

**So: keep `enabled=false`, and change `requireActive`.** A3 is answered, and it
should be recorded as decided rather than left open. The sentinel hash stays as
defence in depth, and the kind allowlist at `JdbcDurableSessions:64` remains the
primary fence.

---

## What A1 changes in the graph

- **A2** shrinks: the sign-in fence is an allowlist, so this becomes a
  regression test pinning it, not an investigation.
- **A3** is **decided**: `enabled=false`, `requireActive` learns the kind. F3 is
  its justification.
- **A4** gains a constraint: whatever handle scheme it picks must not collide
  with the `contained_principals` UUID spelling the self arms compare against,
  because F2's rewrite has to tell the two apart during migration.
- **B2** is re-scoped from "confirm no change" to the graph's highest-risk item,
  and acquires a second justification burden in A5.
- **B1, B3, C3** become **one atomic change** with B2 rather than a sequence —
  F2 means the old self arms and the new grant path cannot coexist across a
  release boundary.
- **The fallback is unaffected** and remains available: it needs none of this.

## What A1 did not do

It did not audit the nineteen rows execution *does* reach. Those are closed by
predicates — kind fences, grant resolution, project-name keying — and several
of them (`ProjectAuthorization.require`, the `role` family, the two project
listings) **will change their answer** once B3 resolves a role for the proxy.
That is the intended capability, but it is a separate audit and it belongs in
A5's compatibility analysis. It is the obvious next gap and it is named here
rather than silently left.
