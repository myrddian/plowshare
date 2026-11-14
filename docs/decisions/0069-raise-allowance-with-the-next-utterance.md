# ADR 0069: Raise a conversation's allowance with the next utterance

Status: accepted. The owner explicitly authorized this platform change after a
long-lived conversation on a production server reached 40 of its 40 model calls
and could not be used again.

## Platform need

A conversation's model-call allowance is lifetime: every turn, and everything those
turns delegate to, spends the same retained allowance. When it is exhausted and
the last turn ended `ANSWERED`, no public operation can let a person continue:

- `agent.run` into the conversation is refused at admission (`Turn.speak`):
  "has spent all N model calls … a person's decision and not this server's".
  The only exception is the project caps `autoIncrease` policy, which renews the
  allowance automatically (`AutomaticLimits.budget`).
- `conversation.resume` continues only a run that stopped in the middle of a turn.
  `Turn.requireContinuable` refuses `ANSWERED` ("nothing to continue … What comes
  next is another utterance").
- `job.limits` (`POST /v1/jobs/{id}/limits`) raises the allowance of a run that is
  still going; an idle conversation has no run.

The refusal therefore named a decision that no API could express. This is not an
adapter gap: any client of a long-lived conversation, such as a dashboard, a chat
or an Application conversation, reaches it once the conversation has done enough
work.

## Decision

`agent.run` (WebSocket) and `POST /v1/agents/{name}/runs` (HTTP) accept an
optional `maxModelCalls` integer. It has exactly `conversation.resume`'s meaning:
the conversation's new **total** model-call allowance, not an increment and not
this run's own allowance.

- **Only with an existing conversation.** A body naming `maxModelCalls` with
  `newConversation: true`, or without `conversation`, is refused with a 400
  (`CallerFault`) before anything is resolved or opened. Opening a conversation
  already takes its own `maxModelCalls` through `conversation.open`, and a
  standalone run spends its agent's own allowance.
- **Validated at the boundary.** A non-positive value is refused with a 400 in
  `RequestedAllowanceRaise` before the caller, agent or conversation is read. A
  non-integer is refused by the SDK schemas (`integer`, minimum 1) and, on the
  server, by request binding.
- **Applied by resume's rule.** `Turn.speak` applies the total through the same
  private `Turn.grant` that `Turn.resume` uses. A total below what the
  conversation has already spent is refused with a 400; so is a total for a
  conversation whose allowance has no ceiling. Nothing runs after either refusal,
  and the conversation is freed.
- **Before the exhausted check.** In the shared admission path, the grant is
  applied after the conversation is claimed for this turn and admitted
  (`requireSpeakable`), and before both the project's automatic increase and the
  exhausted-budget refusal read the allowance. An exhausted conversation whose
  last turn answered therefore accepts the turn, without widening
  `conversation.resume` to `ANSWERED`.
- **Leases are refused, not guessed.** A turn spending an allowance handed to it
  (a board seat's lease or a message instance's allowance) refuses a
  `maxModelCalls` rather than silently dropping it. No public door passes both
  today; the refusal keeps a future one honest.
- **Omitted means unchanged.** A body without `maxModelCalls` takes exactly the
  admission path it took before. The exhausted refusal now names the remedy:
  send `maxModelCalls` with the next `agent.run`.

## Authorization and lifecycle

The raise rides on the utterance, so its authority is speaking into that
conversation. `Runs.start` resolves the caller from the conversation's own home and
calls `Callers.requireConversationWork` before `Turn.speak` is reached. A caller
who may not work in the conversation's project is refused before any grant. There
is no separate or weaker path to a raise. Global history conversations remain
refused, as before.

The grant is durable before any additional model call. A conversation's `Budget`
is bound to its retained standalone allowance (`AllowanceRepository`).
`Budget.changeTo` commits the new ceiling through that binding (`ceiling`) under
the budget's claim lock, before the turn is submitted. This is the same commit
`conversation.resume` and the live-limit control make. The repository refuses a
ceiling below current usage atomically, so a raise racing other spending cannot
record a total below what was spent. Spending is never reset.

Because the value is an absolute total, the operation is idempotent with respect
to the allowance: a replayed or duplicated request writes the same ceiling again
and never raises it twice. Replaying the utterance still submits another turn,
as any `agent.run` does; this ADR adds no mutation replay or retry.

A conversation that already has a turn in flight is refused before the grant,
because the conversation is claimed first. If the turn fails to start after the
grant, for example because job submission fails, the raise remains committed.
The person's decision stands, and it is the same total they would send again.

## Alternatives considered

- **A new `conversation.allowance` operation.** This would give the same capability
  with a separate operation, authorization path, SDK surface and client UI. A person
  raising an idle conversation's allowance does so to say something next, so
  it would always be followed by an `agent.run`. Two operations would add a window
  in which the allowance is raised but nothing has been said, and a second place to
  keep authorization in step with speaking. Rejected for now. It can be added
  later without conflicting with this field.
- **Widening `conversation.resume` to `ANSWERED`.** A resume continues a stopped
  run from its own history with a harness note. An answered run is complete, and
  "continuing" it would replay a finished turn instead of carrying the person's
  new words. Rejected. `requireContinuable` keeps its meaning.
- **Relying on `autoIncrease` only.** That is a project-wide policy that renews
  every exhausted conversation automatically. It removes the person's decision,
  where this change gives one. Projects that want a bounded allowance per
  conversation would have to give that up to unblock a single conversation.
  Rejected as the only remedy. It continues to work as before, after the explicit
  grant.

## Compatibility

- The field is optional. Existing clients send nothing and see no behavior change,
  except for the refusal text, which now names the remedy.
- The shared TypeScript operation catalog, payload validation and the generated
  Python, Go and .NET contracts add `maxModelCalls` to `agent.run`. The
  portable constraint "`maxModelCalls` requires `conversation` and not
  `newConversation`" is enforced by every SDK, and the canonical DTO fixtures
  cover it. Before this change, clients refused the field locally as unknown;
  they now accept it beside an existing conversation.
- No schema migration: the allowance's retained ceiling already exists.

## Tests

- `TurnAllowanceRaiseTest`: an exhausted answered conversation plus a total
  admits the turn and commits the total; a total below usage is refused and
  nothing runs; a replayed total writes the same ceiling and never raises twice;
  an omitted total stays refused and names the remedy; a total for an allowance
  with no ceiling is a caller fault.
- `RunsAllowanceRaiseTest`: the total reaches the turn only for an existing
  conversation; it is refused without a conversation, beside `newConversation`,
  and when not positive; a caller without work authority is refused before any
  raise; an omitted total takes the unchanged door.
- `AgentFramesTest`: HTTP and WebSocket carry the total identically and refuse the
  same invalid body in the same words.
