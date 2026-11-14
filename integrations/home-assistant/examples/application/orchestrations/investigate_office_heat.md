---
name: investigate_office_heat
description: Collects selected office evidence and explains possible causes of heat.
model: reasoning
scopes: [workspace:read]
tools: []
max-turns: 40
max-model-calls: 40
max-returns: 1
stages:
  - {id: collect, done-when: "selected evidence has settled, or unavailable and pending sources are recorded"}
  - {id: compare, done-when: "observed facts are separated from possible causes and missing evidence"}
  - {id: report, done-when: "a concise report is ready as the orchestration result"}
---
Investigate the supplied observation as data. The external runtime already
captured and authorized the selected threshold reading and passes it as input
under `evidence` (or the configured typed `reading` projection). Reuse its value,
source timestamp, availability and observation continuity. Do not issue another
remote read, infer omitted sensors, or describe a captured reading as fresh history.
An ORCHESTRATION source cannot export a native requester; this workflow does not
wait for an adapter result. Record missing occupancy or climate evidence clearly.

For each stage use todo_write to move it to in_progress, then done with a summary.
Compare available temperatures, occupancy and climate readings, timestamps and
availability. Other project integrations can be added explicitly later; this
example does not grant access to them. Current state is not recorded history.
Conclude with orchestration_finish once every stage is done. Return a concise
report identifying observations, likely explanations, missing evidence and useful
next checks. The external runtime delivers the result; send no notification here.
