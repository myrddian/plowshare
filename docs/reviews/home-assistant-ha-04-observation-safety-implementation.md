# Affected HA-04 review: inference endpoint restriction

Root reopened HA-04 for this authorized HA-09 integration change. The original
HA-04 implementation/review reports remain unchanged.

Only `HomeAssistantActionPolicy` and its action-provider guide change from the
accepted HA-04 production/documentation candidate. New local
`HomeAssistantControlServices` validation and a focused fixture support the change.
`replace` validates the candidate profiles before mutating the policy map or
generation, so a refused replacement retains the prior admitted device profile.

Named device policy refuses the verified HA inference services `conversation.process`,
`ai_task.generate_data` and `ai_task.generate_image`. Selecting a fixed entity does
not prove a nonrecursive provider, since service parameters or HA defaults can select
this package's own entities. No unsafe destination guess or override is introduced.
Read discovery and private manual alias values retain their existing contract;
neither description nor explicit manual composition becomes a named execution grant.

No native transport, receipt delivery/effect semantics, SDK journal, profile
fingerprint, complete target bundle, final queued revocation fence, fresh predicate
verifier or server-owned tool grant changes. `HomeAssistantControlServicesTest`
checks all three service refusals, atomic policy replacement, no invocation through
either named describe/execute operation, ordinary device profiles and the separate
manual value contract.

Coordinator verification passed on this frozen source: **125 cases in 15 classes**,
zero errors/failures/skips, **49 seconds**. This includes seven new control-service
cases and all five existing action fixture classes, plus ten manual adapter cases
and two fake pipeline invocations. Global formatting and a separate `formatCheck`
also passed in four seconds each. Runtime configuration, exact-state routing,
held thresholds, feedback/ancestry, retention and recovery are the other selected
regression classes. The detailed HA-09 report lists the exact selected classes,
saved log hashes and installed HA source evidence.

Java remains frozen for independent reapproval. No Gradle/formatter/live calls
were made by this implementer. These focused fake-transport tests do not establish
live grant behavior or physical effects, and the separate legacy/SDK feedback
composition limitation remains explicit.
