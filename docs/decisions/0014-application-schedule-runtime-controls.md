# Application schedule runtime controls

Status: accepted for implementation

## Platform need

Application schedules must have the same operational Pause/Resume controls as
other schedules on CLI, TUI, desktop and web console. A packaged schedule can be
paused at installation and activated when its external dependencies are ready.
Requiring an administrator to deploy a whole release for that lifecycle action
prevents ordinary runtime control and couples execution state to source authoring.
This is a separately requested scheduling platform change, not an integration
workaround.

## Decision

Keep deployed Application files immutable. The existing `schedule.pause` operation
retains an account-owned pause override on the schedule file's durable projection.
It updates schedule and trigger rows in one transaction and refuses waiting
firings when paused. Existing runs retain their lifecycle. A transition from paused
to active calculates the next future occurrence; repeated resume is idempotent and
does not move an already active clock.

Reconciliation reads and validates the original source definition. Its effective
pause state includes the override in `schedule.files`; `schedule.list` exposes the
corresponding runtime state. An unchanged definition preserves the override across
reconciliation, temporary source suspension, repository reconstruction and server
restart. A changed definition or deletion clears the override, so explicitly
changed deployment configuration takes effect. The original package remains the
source for timing, action, target and grants.

Controls retain the existing registered-account ownership boundary. Application
controls require current project management authority and valid source execution
grants. They cannot transfer ownership, elevate a service token, enable an invalid
source or alter a foreign schedule. The repository fences a changed effective
definition and serializes controls with reconciliation using the source row lock.

## Public clients and alternatives

All SDK languages already expose the same `schedule.pause` contract with a boolean
`paused`; no language-specific operation or wire field is needed. CLI adds
`schedule resume`, mapped to that operation with `paused:false`. TUI already has
`/schedule resume`; desktop already has a Resume button. Web console adds a
Schedules view using its existing authenticated WebSocket.

A new Application revision remains the right mechanism for timing/action/grant
changes. A client-side rewrite, direct database update or HTTP fallback would
bypass release ownership or runtime authorization. A transient in-memory flag
would disappear on restart and be overwritten by reconciliation.

Clients read retained state after a control. Unknown delivery does not trigger
replay; the web view disables the affected control until a fresh listing
establishes the requested state. Controls make no inference call or Relay publish
on their own. The scheduler still publishes due occurrences to `schedule.due`.

## Compatibility and verification

Existing mutable file schedules retain file editing behavior. Deployed source
writes and deletes remain refused. The change adds a nullable projection column
through a new Flyway migration, with no change to existing operation DTOs.

Mocked service tests cover source ownership, management/execution checks and no
Application file writes. Focused PostgreSQL tests cover row mapping, durable
reconciliation, source-default preservation, definition changes, future-fire
semantics, repeated resume and transactional rollback. Client tests cover the
shared facade, rendered controls, escaping, permission refusal and uncertain
delivery without replay. Browser verification uses a controlled cookie/WebSocket
fixture and does not imply that a production server has been upgraded.
