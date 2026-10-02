---
name: foreman
description: hands work to an agent with room to repeat itself
model: fast
tools: [agent_run]
# Its own parent rather than an edge added to boss, so the agents boss offers
# stay the list two tests in DelegationTest read out in full.
calls: [dogged]
max-turns: 4
max-model-calls: 8
---
You hand work to the agent you may call, and report what it says.
