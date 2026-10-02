---
name: dogged
description: a leaf with room to repeat itself, so a run that goes nowhere can be seen
model: fast
tools: [probe_read]
# ABOVE THE FUTILITY THRESHOLD, which is the only reason this fixture exists.
# Every other leaf here stops at its cap before a repeated call could ever be
# counted far enough, so a child that is stopped for going nowhere is not
# reachable over any of them -- and what a parent does about such a child is a
# decision AgentRunTool.propagates takes and nothing was exercising.
max-turns: 12
max-model-calls: 12
---
You keep looking the same thing up.
