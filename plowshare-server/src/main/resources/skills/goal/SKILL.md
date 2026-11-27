---
name: goal
description: Hand off an objective to a dedicated Goal Agent and return the committed goal and inbox details.
mode: DIRECT
agentVisible: true
argument-hint: Describe the objective and any completion criteria. You can also name a Goal Agent.
allowed-tools: goal_candidates goal_start goal_clarify
---
Use the user's task and conversation context to identify the objective and
completion criteria for the handoff. Include the references the Goal Agent
needs. The dedicated conversation starts with the supplied objective and
criteria.

Call `goal_candidates` to list the Goal Agents this conversation can launch.
Use the named agent if it is eligible. If the user names an unavailable agent,
ask them to choose from the eligible candidates. If they leave the target
open, select a candidate suited to the objective when the choice is clear.
Use `goal_clarify` when the objective or target needs a user decision.
Continue the retained invocation after their answer.

Call `goal_start` once with the exact candidate name and the objective. Preserve
the user's constraints. Report the committed goal ID, message address, and
conversation from the receipt. If a bot handling a native message needs a correlated
outcome, set `replyExpected: true` and keep the launch message ID from the
receipt. The handoff completes this skill invocation.
