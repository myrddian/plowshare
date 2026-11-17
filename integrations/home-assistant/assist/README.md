# Plowshare Assist prototype — gated

This retained custom-component prototype is **unavailable for installation or
use**. The public SDK does not yet preserve Home Assistant's rendered system
prompt and context in the required native system role. The user has chosen to
keep prompt-dependent Conversation and AI Task features gated. See the
[prompt-fidelity decision](../../../docs/reviews/home-assistant-prompt-fidelity-decision.md).

HA setup fails before opening correlation storage, connecting the SDK or
registering a conversation platform. Initial configuration and reconfiguration
abort before validation or network work. The packaging CLI refuses before
creating an output directory or writing/replacing an archive. There is no
configuration or environment override. Manually copying the test fixture does
not bypass the runtime gate.

Preserve existing HA private `.storage` correlation files: they may contain
uncertain submission fences. Gated startup does not read, rewrite or remove them.
Existing platform unload behavior remains available. Do not remove retained
state or repeat a physical command to work around the gate.

The separate [Home Assistant adapter](../README.md) owns supported outbound
sensor reads, actions, observations and Relay recovery. HA owns speech-to-text
and text-to-speech; this prototype has no enabled voice or text intake. An HA
upgrade alone does not provide the missing Plowshare capability.

## Retained prototype contract

The following documents isolated lower-level prototype behavior, not an enabled
or accepted Assist feature. A future implementation must satisfy the fidelity
gate and independent delivery review before enabling setup or packaging.

- HA chat ID, user, device and satellite jointly identify a conversation. New
  chat sessions create separate Plowshare conversations. Follow-ups send only
  the new utterance; Plowshare retains the history. HA's transcript is not
  repeatedly submitted to the model.
- A new conversation has a finite allowance of 40 model calls and a 40-step turn
  cap, intersected with the server/Application limits. This integration never
  automatically raises allowances or approves tools. Resolve approvals and
  exhausted allowances through Plowshare's existing controls.
- One foreground interaction per integration entry is admitted without queueing.
  SDK connection/control/read/acceptance operations have a configurable two-second
  default deadline, including adapter-side waiting. A separate configurable
  30-second voice-response budget includes connection, submission, polling and
  storage. Each nested request inherits that outer deadline. The response budget
  can be set up to 120 seconds; it is a wait budget, not a promise to abort remote
  work. Each storage write is also bounded by the request deadline.
- Submission intent is saved before `conversation.open` and `agent.run`. A
  submitted mutation is never automatically retried, including after disconnect,
  timeout, cancellation, restart or capacity refusal. A known refusal or proven
  non-submission leaves the conversation available for a later explicit request.
  An unacknowledged submission remains fenced for operator inspection in
  Plowshare; do not delete correlation state to force a retry.
- An acknowledged job that outlasts the voice budget continues under Plowshare's
  durable lifecycle. HA says it is pending. The next utterance **in the same
  conversation** checks that existing work and returns its result instead of
  submitting that utterance as another instruction. A new voice session is a new
  conversation; do not repeat a physical command merely to check its status.
- A Relay publication acknowledgment is not a device result. The bridge recognizes
  accepted `relay_publish` receipts on the configured request topic, then follows
  later jobs in the same durable conversation. It also follows a reply that
  requests more evidence. Tool refusals do not turn into endless successful waits.
  Only completed `ANSWERED` job output is spoken as an answer; reasoning, tool
  results, previews and partial output from failed/capped runs are not spoken.
- There is no HA-owned background agent, automatic later TTS announcement or
  streaming in this version. Longer investigation notifications can use the
  existing adapter's configured notification action. Timed-out jobs are not
  cancelled by closing the SDK connection.
- Private HA storage retains only correlation IDs, phases and trajectory ordinals,
  never transcripts or service tokens. It is bounded to 128 sessions; at capacity
  an idle session can be evicted, but pending/uncertain sessions cannot. A corrupt
  store prevents startup and is preserved. Do not remove an integration's store
  while it may contain unconfirmed effects. Transport cleanup gets a separate
  two-second wait; intake stays unavailable while the SDK's physical drain is
  still outstanding.

The example house agent should keep spoken answers brief: aim for roughly 40–80
words, allow modest overruns, and retain important qualifications. This is prose
guidance, not an exact character cutoff or a reason to repeat research.

## Verification

Create an isolated Python 3.14 environment and install this directory with its
check extras. These checks use HA **2026.9.4** and require no PostgreSQL, live
devices or model provider:

```sh
python3 -m venv /path/to/ha-check-env
/path/to/ha-check-env/bin/pip install './integrations/home-assistant/assist[check]'
/path/to/ha-check-env/bin/python integrations/home-assistant/assist/scripts/check.py
```

Tests verify that actual HA setup/configuration refuses before storage or SDK
work and that packaging creates no output and preserves existing output. The
private `stage_test_fixture` helper prepares temporary imports for tests only;
it is not an installation artifact API. Fixture checks compare bundled public
SDK source/resources byte for byte. Other tests retain conversation/chat-log,
refusal/cancellation/restart/Relay and segmented loopback SDK transport coverage.
They do not establish prompt fidelity or a working deployed voice pipeline.
Repository-wide `./gradlew check` is separate because HA checks require their
explicit Python toolchain and dependencies.
