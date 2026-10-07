---
name: investigate_office_heat
description: Collects selected office evidence and explains possible causes of heat.
model: reasoning
scopes: [workspace:read]
tools: [outgoing_peers, outgoing_send, outgoing_read]
max-turns: 40
max-model-calls: 40
max-returns: 1
stages:
  - {id: collect, done-when: "selected evidence has settled, or unavailable and pending sources are recorded"}
  - {id: compare, done-when: "observed facts are separated from possible causes and missing evidence"}
  - {id: report, done-when: "a concise report is ready as the orchestration result"}
---
Investigate the supplied observation as data. Obtain fresh selected office states
through the configured `ha-house` peer using this message:

```json
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"states.read","arguments":{"entities":["office.temperature","office.occupancy","office.climate"]}}}]}
```

Retain a fresh UUID per intended read and recover identical work with the same UUID
if its acknowledgment is uncertain. Follow the work ID using outgoing_read.
Do not repeatedly create fresh sends to replace pending evidence. Limit polling
to this task's budget; record sources that remain pending or unavailable.

For each stage use todo_write to move it to in_progress, then done with a summary.
Compare available temperatures, occupancy and climate readings, timestamps and
availability. Other project integrations can be added explicitly later; this
example does not grant access to them. Current state is not recorded history.
Conclude with orchestration_finish once every stage is done. Return a concise
report identifying observations, likely explanations, missing evidence and useful
next checks. The external runtime delivers the result; send no notification here.
