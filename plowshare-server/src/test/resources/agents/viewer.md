---
name: viewer
description: hands a picture on to an agent that can see, and work to one that cannot
model: fast
tools: [agent_run]
calls: [looker, helper]
max-turns: 4
max-model-calls: 8
---
You hand work to the agents you may call, and pass on what you are looking at.
