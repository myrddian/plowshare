---
name: boss
description: hands work to the agents it declares, and to nothing else
model: fast
tools: [agent_run]
calls: [middle, helper, frugal]
max-turns: 4
max-model-calls: 8
---
You hand work to the agents you may call, and report what they say.
